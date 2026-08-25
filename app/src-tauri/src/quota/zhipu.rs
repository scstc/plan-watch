//! 智谱 GLM 套餐余额查询（个人版；团队版 v0.4 再加 `?type=2` + org/project 头）。
//!
//! 接口调研：docs/providers/zhipu.md（2026-08-25 实测）。
//! 解析逻辑移植自 cc-switch `services/coding_plan.rs` 的
//! `query_zhipu` / `parse_zhipu_token_tiers`（含 issue #3036 修复）。

use serde_json::Value;

use super::{classify_http_error, http, millis_to_iso8601, parse_f64, QuotaTier, QueryOutcome, WindowKind};
use crate::config::Region;

fn quota_base(region: Region) -> &'static str {
    match region {
        Region::Cn => "https://open.bigmodel.cn",
        Region::Global => "https://api.z.ai",
    }
}

pub async fn query(region: Region, api_key: &str) -> QueryOutcome {
    let url = format!("{}/api/monitor/usage/quota/limit", quota_base(region));
    let resp = match http()
        .get(&url)
        // 智谱 Key 原样放 Authorization 头，不加 Bearer 前缀
        .header("Authorization", api_key)
        .header("Accept-Language", "en-US,en")
        .send()
        .await
    {
        Ok(r) => r,
        Err(e) => return QueryOutcome::NetworkError(format!("Network error: {e}")),
    };

    let status = resp.status();
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        // 401/403 → 鉴权失败；429/5xx → 瞬时（保留旧数据）；其余 → 业务错误
        return classify_http_error(status, &body);
    }

    // 先 bytes() 再解析：读体失败（超时/连接中断）是瞬时 → NetworkError；
    // 拿到完整响应体后解析失败才是确定性 BusinessError。
    let raw = match resp.bytes().await {
        Ok(b) => b,
        Err(e) => return QueryOutcome::NetworkError(format!("Failed to read response: {e}")),
    };
    let body: Value = match serde_json::from_slice(&raw) {
        Ok(v) => v,
        Err(e) => return QueryOutcome::BusinessError(format!("Failed to parse response: {e}")),
    };

    quota_from_body(&body)
}

/// 解析响应体（无网络 IO，纯函数便于单测）。
/// 关键坑：Key 无效时返回 HTTP 200 + `{"code":401,...,"success":false}`，
/// 凭证失效识别不能依赖 HTTP 状态码。
fn quota_from_body(body: &Value) -> QueryOutcome {
    if body.get("success").and_then(|v| v.as_bool()) == Some(false) {
        let msg = body
            .get("msg")
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown error");
        let code = body
            .get("code")
            .and_then(|v| v.as_i64().or_else(|| v.as_str().and_then(|s| s.parse().ok())))
            .unwrap_or(0);
        if code == 401 {
            return QueryOutcome::AuthExpired(msg.to_string());
        }
        return QueryOutcome::BusinessError(format!("API error (code {code}): {msg}"));
    }

    let Some(data) = body.get("data") else {
        return QueryOutcome::BusinessError("Missing 'data' field in response".to_string());
    };

    let plan_level = data
        .get("level")
        .and_then(|v| v.as_str())
        .map(String::from);

    QueryOutcome::Success {
        plan_level,
        tiers: parse_zhipu_tiers(data),
    }
}

enum ZhipuWindow {
    FiveHour,
    Weekly,
}

/// 窗口分类靠 `unit` 字段：3 → 5 小时，6 → 每周。
/// `number` 实测有 7 和 1 两种取值，只锚定 `unit`。
fn classify_zhipu_window(item: &Value) -> Option<ZhipuWindow> {
    match item.get("unit").and_then(|v| v.as_i64()) {
        Some(3) => Some(ZhipuWindow::FiveHour),
        Some(6) => Some(ZhipuWindow::Weekly),
        _ => None,
    }
}

/// (reset_ms, percentage, reset_iso, used, total, remaining)
type Entry = (Option<i64>, f64, Option<String>, Option<f64>, Option<f64>, Option<f64>);

fn entry_from_limit(item: &Value) -> Entry {
    let percentage = item
        .get("percentage")
        .and_then(parse_f64)
        .unwrap_or(0.0);
    let reset_ms = item.get("nextResetTime").and_then(|v| v.as_i64());
    let reset_iso = reset_ms.and_then(millis_to_iso8601);
    (
        reset_ms,
        percentage,
        reset_iso,
        item.get("currentValue").and_then(parse_f64),
        item.get("usage").and_then(parse_f64),
        item.get("remaining").and_then(parse_f64),
    )
}

/// 把智谱 `data.limits[]` 解析成 tier 列表。
///
/// 分类优先级：
/// 1. 显式字段：`unit` 标识窗口类型。不能按 `nextResetTime` 排序代替——
///    周期末尾每周窗口会比 5 小时窗口更早重置（cc-switch issue #3036），
///    时间排序在该场景必然把两桶标反。
/// 2. 兜底启发式（`unit` 缺失或不识别）：无 `nextResetTime` 的条目优先归
///    five_hour（5 小时桶在 0% 等状态下可能没有 reset），其余按 reset 升序
///    依次填入仍空缺的槽位。
///
/// 老套餐（2026-02-12 前订阅）只回 1 条 limit → 降级只展示 5 小时桶。
fn parse_zhipu_tiers(data: &Value) -> Vec<QuotaTier> {
    let mut five_hour: Option<Entry> = None;
    let mut weekly: Option<Entry> = None;
    let mut unclassified: Vec<Entry> = Vec::new();

    if let Some(limits) = data.get("limits").and_then(|v| v.as_array()) {
        for limit_item in limits {
            let limit_type = limit_item
                .get("type")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            // TOKENS_LIMIT / CREDIT_LIMIT 都要识别，大小写不敏感
            if !(limit_type.eq_ignore_ascii_case("TOKENS_LIMIT")
                || limit_type.eq_ignore_ascii_case("CREDIT_LIMIT"))
            {
                continue;
            }
            let entry = entry_from_limit(limit_item);
            match classify_zhipu_window(limit_item) {
                Some(ZhipuWindow::FiveHour) if five_hour.is_none() => five_hour = Some(entry),
                Some(ZhipuWindow::Weekly) if weekly.is_none() => weekly = Some(entry),
                _ => unclassified.push(entry),
            }
        }
    }

    // None 在前（无 reset 的优先归 5h 槽），其余按 reset 升序填空位
    unclassified.sort_by_key(|(reset, _, _, _, _, _)| (reset.is_some(), reset.unwrap_or(i64::MIN)));
    for entry in unclassified {
        if five_hour.is_none() {
            five_hour = Some(entry);
        } else if weekly.is_none() {
            weekly = Some(entry);
        }
        // 智谱当前最多两条 TOKENS/CREDIT_LIMIT，多余的忽略
    }

    let mut tiers = Vec::new();
    for (kind, slot) in [(WindowKind::FiveHour, five_hour), (WindowKind::Weekly, weekly)] {
        if let Some((_, percentage, resets_at, used, total, remaining)) = slot {
            tiers.push(QuotaTier {
                window: kind,
                used_percent: percentage,
                resets_at,
                used,
                total,
                remaining,
                unlimited: false,
            });
        }
    }
    tiers
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn quota_from_body_success_extracts_level_and_tiers() {
        // 实测响应样例（docs/providers/zhipu.md）
        let body = json!({
            "code": 200,
            "msg": "操作成功",
            "data": {
                "limits": [
                    { "type": "CREDIT_LIMIT", "unit": 3, "number": 5,
                      "usage": 2000, "currentValue": 0, "remaining": 2000, "percentage": 0 },
                    { "type": "CREDIT_LIMIT", "unit": 6, "number": 1,
                      "usage": 10000, "currentValue": 4788, "remaining": 5211,
                      "percentage": 47, "nextResetTime": 1_787_919_529_998_i64 }
                ],
                "level": "lite"
            },
            "success": true
        });
        let QueryOutcome::Success { plan_level, tiers } = quota_from_body(&body) else {
            panic!("expected success");
        };
        assert_eq!(plan_level.as_deref(), Some("lite"));
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[0].used_percent, 0.0);
        assert_eq!(tiers[0].total, Some(2000.0));
        assert_eq!(tiers[0].used, Some(0.0));
        assert_eq!(tiers[0].remaining, Some(2000.0));
        assert_eq!(tiers[1].window, WindowKind::Weekly);
        assert_eq!(tiers[1].used_percent, 47.0);
        assert_eq!(tiers[1].total, Some(10000.0));
        assert_eq!(tiers[1].used, Some(4788.0));
        assert!(tiers[1].resets_at.is_some());
    }

    #[test]
    fn business_401_with_http_200_is_auth_expired() {
        // 智谱特色：Key 无效时 HTTP 200 + code:401，必须识别为凭证失效
        let body = json!({
            "code": 401,
            "msg": "令牌已过期或验证不正确",
            "success": false
        });
        match quota_from_body(&body) {
            QueryOutcome::AuthExpired(msg) => assert_eq!(msg, "令牌已过期或验证不正确"),
            other => panic!("expected AuthExpired, got {other:?}"),
        }
    }

    #[test]
    fn business_error_other_than_401_is_business() {
        let body = json!({ "code": 1000, "msg": "boom", "success": false });
        assert!(matches!(
            quota_from_body(&body),
            QueryOutcome::BusinessError(_)
        ));
    }

    #[test]
    fn success_true_without_data_field_is_business_error() {
        let body = json!({ "success": true });
        assert!(matches!(
            quota_from_body(&body),
            QueryOutcome::BusinessError(_)
        ));
    }

    #[test]
    fn new_plan_two_tiers_sorted_by_reset_time() {
        // 无 unit 时按 reset 时间兜底：较近归 5h、较远归周。
        // 故意把"周限"放数组前面，验证不依赖输入顺序。
        let data = json!({
            "limits": [
                { "type": "TOKENS_LIMIT", "percentage": 53.0, "nextResetTime": 2_000_000_000_000_i64 },
                { "type": "TOKENS_LIMIT", "percentage": 44.0, "nextResetTime": 1_000_000_000_000_i64 },
                { "type": "TIME_LIMIT",   "percentage":  7.0 },
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[0].used_percent, 44.0);
        assert_eq!(tiers[1].window, WindowKind::Weekly);
        assert_eq!(tiers[1].used_percent, 53.0);
    }

    #[test]
    fn old_plan_single_tier_falls_back_to_five_hour() {
        let data = json!({
            "limits": [
                { "type": "TOKENS_LIMIT", "percentage": 2.0, "nextResetTime": 1_774_967_594_803_i64 },
                { "type": "TIME_LIMIT", "percentage": 0.0 }
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 1);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[0].used_percent, 2.0);
    }

    #[test]
    fn no_token_limits_returns_empty() {
        let data = json!({ "limits": [{ "type": "TIME_LIMIT", "percentage": 5.0 }] });
        assert!(parse_zhipu_tiers(&data).is_empty());
    }

    #[test]
    fn missing_reset_time_is_five_hour_when_weekly_has_reset() {
        // 真实反馈：5 小时桶为 0% 时可能没有 nextResetTime；每周桶带 reset。
        // 这种形态不能按 reset 升序把每周桶误判为 five_hour。
        let data = json!({
            "limits": [
                { "type": "TOKENS_LIMIT", "percentage": 25.0, "nextResetTime": 2_000_000_000_000_i64 },
                { "type": "TOKENS_LIMIT", "percentage": 0.0 }
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[0].used_percent, 0.0);
        assert!(tiers[0].resets_at.is_none());
        assert_eq!(tiers[1].window, WindowKind::Weekly);
        assert_eq!(tiers[1].used_percent, 25.0);
        assert!(tiers[1].resets_at.is_some());
    }

    #[test]
    fn type_is_case_insensitive() {
        // 防御性：上游若把 "TOKENS_LIMIT" 改成小写或驼峰仍能识别
        let data = json!({
            "limits": [
                { "type": "tokens_limit", "percentage": 12.0, "nextResetTime": 1_000_000_000_000_i64 },
                { "type": "Tokens_Limit", "percentage": 34.0, "nextResetTime": 2_000_000_000_000_i64 }
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].used_percent, 12.0);
        assert_eq!(tiers[1].used_percent, 34.0);
    }

    #[test]
    fn invalid_percentage_falls_back_to_zero() {
        // percentage 为字符串或 null 时不崩溃，按 0 处理
        let data = json!({
            "limits": [
                { "type": "TOKENS_LIMIT", "percentage": "invalid", "nextResetTime": 1_000_000_000_000_i64 },
                { "type": "TOKENS_LIMIT", "percentage": null,      "nextResetTime": 2_000_000_000_000_i64 }
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].used_percent, 0.0);
        assert_eq!(tiers[1].used_percent, 0.0);
    }

    #[test]
    fn extreme_percentage_values_pass_through() {
        // 负数 / 超 100 不裁剪——解析层忠实搬运，展示层负责显示策略
        let data = json!({
            "limits": [
                { "type": "TOKENS_LIMIT", "percentage": -5.0,  "nextResetTime": 1_000_000_000_000_i64 },
                { "type": "TOKENS_LIMIT", "percentage": 150.0, "nextResetTime": 2_000_000_000_000_i64 }
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].used_percent, -5.0);
        assert_eq!(tiers[1].used_percent, 150.0);
    }

    #[test]
    fn unit_field_overrides_reset_order_when_weekly_resets_sooner() {
        // 真实案例（issue #3036）：每周周期末尾，周桶比 5h 桶更早重置，
        // 旧逻辑按 reset 升序必然标反，unit 字段必须优先。
        let data = json!({
            "limits": [
                { "type": "TOKENS_LIMIT", "unit": 6, "number": 7, "percentage": 42.0, "nextResetTime": 1_000_003_600_000_i64 },
                { "type": "TOKENS_LIMIT", "unit": 3, "number": 5, "percentage": 1.0,  "nextResetTime": 1_000_018_000_000_i64 }
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[0].used_percent, 1.0);
        assert_eq!(tiers[1].window, WindowKind::Weekly);
        assert_eq!(tiers[1].used_percent, 42.0);
    }

    #[test]
    fn weekly_unit_six_number_one_variant() {
        // (unit:6, number:1) 也是每周窗口——分类只看 unit，number 不影响
        let data = json!({
            "limits": [
                { "type": "TOKENS_LIMIT", "unit": 6, "number": 1, "percentage": 30.0, "nextResetTime": 1_000_000_000_000_i64 },
                { "type": "TOKENS_LIMIT", "unit": 3, "number": 5, "percentage": 10.0, "nextResetTime": 2_000_000_000_000_i64 }
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[0].used_percent, 10.0);
        assert_eq!(tiers[1].window, WindowKind::Weekly);
        assert_eq!(tiers[1].used_percent, 30.0);
    }

    #[test]
    fn partial_unit_fields_fill_remaining_slot() {
        // 只有周桶带 unit 时，缺 unit 的另一条填入剩余的 5h 槽位，
        // 即便它的 reset 更晚——显式分类不受时间排序干扰。
        let data = json!({
            "limits": [
                { "type": "TOKENS_LIMIT", "unit": 6, "number": 7, "percentage": 42.0, "nextResetTime": 1_000_000_000_000_i64 },
                { "type": "TOKENS_LIMIT", "percentage": 1.0, "nextResetTime": 2_000_000_000_000_i64 }
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[0].used_percent, 1.0);
        assert_eq!(tiers[1].window, WindowKind::Weekly);
        assert_eq!(tiers[1].used_percent, 42.0);
    }

    #[test]
    fn unknown_unit_values_fall_back_to_reset_order() {
        // 未识别的 unit 枚举值不猜语义，整体回落重置时间启发式
        let data = json!({
            "limits": [
                { "type": "TOKENS_LIMIT", "unit": 9, "percentage": 44.0, "nextResetTime": 1_000_000_000_000_i64 },
                { "type": "TOKENS_LIMIT", "unit": 9, "percentage": 53.0, "nextResetTime": 2_000_000_000_000_i64 }
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].used_percent, 44.0);
        assert_eq!(tiers[1].used_percent, 53.0);
    }

    #[test]
    fn duplicate_unit_classification_fills_other_slot() {
        // 防御性：两条都标成 5 小时窗（上游异常）时，第一条占 5h，
        // 第二条走兜底填入 weekly，不丢数据也不 panic。
        let data = json!({
            "limits": [
                { "type": "TOKENS_LIMIT", "unit": 3, "number": 5, "percentage": 10.0, "nextResetTime": 1_000_000_000_000_i64 },
                { "type": "TOKENS_LIMIT", "unit": 3, "number": 5, "percentage": 20.0, "nextResetTime": 2_000_000_000_000_i64 }
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].used_percent, 10.0);
        assert_eq!(tiers[1].used_percent, 20.0);
    }

    #[test]
    fn more_than_two_token_limits_keeps_first_two() {
        let data = json!({
            "limits": [
                { "type": "TOKENS_LIMIT", "percentage": 1.0, "nextResetTime": 1_000_000_000_000_i64 },
                { "type": "TOKENS_LIMIT", "percentage": 2.0, "nextResetTime": 2_000_000_000_000_i64 },
                { "type": "TOKENS_LIMIT", "percentage": 3.0, "nextResetTime": 3_000_000_000_000_i64 }
            ]
        });
        let tiers = parse_zhipu_tiers(&data);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[1].window, WindowKind::Weekly);
    }
}
