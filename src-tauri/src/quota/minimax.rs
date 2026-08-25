//! MiniMax 编程套餐余额查询。
//!
//! 接口调研：docs/providers/minimax.md（2026-08-25 实测）。
//! 解析逻辑移植自 cc-switch `services/coding_plan.rs` 的
//! `query_minimax` / `parse_minimax_tiers`。

use serde_json::Value;

use super::{classify_http_error, http, millis_to_iso8601, QuotaTier, QueryOutcome, WindowKind};
use crate::config::Region;

fn api_domain(region: Region) -> &'static str {
    match region {
        Region::Cn => "api.minimaxi.com",
        Region::Global => "api.minimax.io",
    }
}

pub async fn query(region: Region, api_key: &str) -> QueryOutcome {
    let url = format!(
        "https://{}/v1/api/openplatform/coding_plan/remains",
        api_domain(region)
    );
    let resp = match http()
        .get(&url)
        .header("Authorization", format!("Bearer {api_key}"))
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

    // 先 bytes() 再解析：读体失败是瞬时 → NetworkError；解析失败才是确定性
    let raw = match resp.bytes().await {
        Ok(b) => b,
        Err(e) => return QueryOutcome::NetworkError(format!("Failed to read response: {e}")),
    };
    let body: Value = match serde_json::from_slice(&raw) {
        Ok(v) => v,
        Err(e) => return QueryOutcome::BusinessError(format!("Failed to parse response: {e}")),
    };

    outcome_from_body(&body)
}

/// 解析响应体（无网络 IO，纯函数便于单测）。
/// 业务层错误：`base_resp.status_code != 0`。
/// 例外：1004（"cookie is missing, log in again"，实测 2026-08-25）本质是
/// 凭证失效——HTTP 层仍是 200，必须归到 AuthExpired 才能提示用户换 Key。
fn outcome_from_body(body: &Value) -> QueryOutcome {
    if let Some(base_resp) = body.get("base_resp") {
        let status_code = base_resp
            .get("status_code")
            .and_then(|v| v.as_i64())
            .unwrap_or(-1);
        if status_code != 0 {
            let msg = base_resp
                .get("status_msg")
                .and_then(|v| v.as_str())
                .unwrap_or("Unknown error");
            if status_code == 1004 {
                return QueryOutcome::AuthExpired(msg.to_string());
            }
            return QueryOutcome::BusinessError(format!("API error (code {status_code}): {msg}"));
        }
    }

    QueryOutcome::Success {
        plan_level: None,
        tiers: parse_minimax_tiers(body),
    }
}

/// 从 `/coding_plan/remains` 响应解析额度 tier。
///
/// 语义坑：`current_*_remaining_percent` 是**剩余**百分比（0–100），取反才是已用。
/// `model_remains[]` 里只取 `model_name == "general"`（编程套餐），跳过 video 等。
/// 5h 桶始终存在；周桶靠 `current_weekly_status == 1` 判定激活——
/// 无周限额套餐该字段为 3 且 remaining_percent 恒为 100，必须跳过。
fn parse_minimax_tiers(body: &Value) -> Vec<QuotaTier> {
    let mut tiers = Vec::new();

    let Some(model_remains) = body.get("model_remains").and_then(|v| v.as_array()) else {
        return tiers;
    };

    let Some(item) = model_remains.iter().find(|item| {
        item.get("model_name")
            .and_then(|v| v.as_str())
            .map(|s| s == "general")
            .unwrap_or(false)
    }) else {
        return tiers;
    };

    // 5h 桶：剩余百分比 → 已用百分比
    if let Some(remain_pct) = item
        .get("current_interval_remaining_percent")
        .and_then(|v| v.as_f64())
    {
        let resets_at = item
            .get("end_time")
            .and_then(|v| v.as_i64())
            .and_then(millis_to_iso8601);
        tiers.push(QuotaTier {
            window: WindowKind::FiveHour,
            used_percent: 100.0 - remain_pct,
            resets_at,
            used: None,
            total: None,
            remaining: None,
        });
    }

    // 周桶：仅当 status == 1 时激活；3 等表示无周限额，跳过
    if item.get("current_weekly_status").and_then(|v| v.as_i64()) == Some(1) {
        if let Some(remain_pct) = item
            .get("current_weekly_remaining_percent")
            .and_then(|v| v.as_f64())
        {
            let resets_at = item
                .get("weekly_end_time")
                .and_then(|v| v.as_i64())
                .and_then(millis_to_iso8601);
            tiers.push(QuotaTier {
                window: WindowKind::Weekly,
                used_percent: 100.0 - remain_pct,
                resets_at,
                used: None,
                total: None,
                remaining: None,
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
    fn business_error_from_base_resp() {
        // 业务层错误：base_resp.status_code != 0 → BusinessError
        let body = json!({
            "base_resp": { "status_code": 1000, "status_msg": "internal error" }
        });
        match outcome_from_body(&body) {
            QueryOutcome::BusinessError(msg) => assert!(msg.contains("1000")),
            other => panic!("expected BusinessError, got {other:?}"),
        }
    }

    #[test]
    fn code_1004_is_auth_expired() {
        // 实测（2026-08-25）：key 失效返回 HTTP 200 + code 1004
        // "cookie is missing, log in again"——本质是凭证失效，归 AuthExpired
        let body = json!({
            "base_resp": { "status_code": 1004, "status_msg": "cookie is missing, log in again" }
        });
        match outcome_from_body(&body) {
            QueryOutcome::AuthExpired(msg) => assert!(msg.contains("log in again")),
            other => panic!("expected AuthExpired, got {other:?}"),
        }
    }

    #[test]
    fn success_body_returns_tiers() {
        // status_code == 0 正常放行到 tier 解析
        let body = json!({
            "model_remains": [{
                "model_name": "general",
                "current_interval_remaining_percent": 90.0
            }],
            "base_resp": { "status_code": 0, "status_msg": "success" }
        });
        match outcome_from_body(&body) {
            QueryOutcome::Success { tiers, .. } => assert_eq!(tiers.len(), 1),
            other => panic!("expected Success, got {other:?}"),
        }
    }

    #[test]
    fn general_two_tiers_from_remaining_percent() {
        // 主路径：general 桶 5h 剩 98% / weekly 剩 95% → 已用 2% / 5%
        let body = json!({
            "model_remains": [
                {
                    "model_name": "general",
                    "current_interval_remaining_percent": 98.0,
                    "current_weekly_remaining_percent": 95.0,
                    "current_interval_status": 1,
                    "current_weekly_status": 1,
                    "end_time": 1_780_329_600_000_i64,
                    "weekly_end_time": 1_780_848_000_000_i64
                },
                {
                    "model_name": "video",
                    "current_interval_remaining_percent": 100.0,
                    "current_weekly_remaining_percent": 100.0
                }
            ],
            "base_resp": { "status_code": 0, "status_msg": "success" }
        });
        let tiers = parse_minimax_tiers(&body);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[0].used_percent, 2.0);
        assert!(tiers[0].resets_at.is_some());
        assert_eq!(tiers[1].window, WindowKind::Weekly);
        assert_eq!(tiers[1].used_percent, 5.0);
        assert!(tiers[1].resets_at.is_some());
    }

    #[test]
    fn skips_video_and_finds_general_in_any_position() {
        // 防御性：video 排前面、general 排后面，仍能定位
        let body = json!({
            "model_remains": [
                {
                    "model_name": "video",
                    "current_interval_remaining_percent": 50.0,
                    "current_weekly_remaining_percent": 50.0
                },
                {
                    "model_name": "general",
                    "current_interval_remaining_percent": 80.0,
                    "current_weekly_remaining_percent": 70.0,
                    "current_interval_status": 1,
                    "current_weekly_status": 1
                }
            ]
        });
        let tiers = parse_minimax_tiers(&body);
        assert_eq!(tiers.len(), 2);
        // 取的是 general 桶，不是 video（20%/30% 而非 50%/50%）
        assert_eq!(tiers[0].used_percent, 20.0);
        assert_eq!(tiers[1].used_percent, 30.0);
    }

    #[test]
    fn missing_general_returns_empty() {
        // 只有 video / 空数组 / 缺字段 → 不崩溃，tiers 为空
        let body = json!({
            "model_remains": [
                { "model_name": "video", "current_interval_remaining_percent": 100.0 }
            ]
        });
        assert!(parse_minimax_tiers(&body).is_empty());

        let body_empty = json!({ "model_remains": [] });
        assert!(parse_minimax_tiers(&body_empty).is_empty());

        let body_no_field = json!({});
        assert!(parse_minimax_tiers(&body_no_field).is_empty());
    }

    #[test]
    fn missing_percent_fields_skips_tier() {
        // 字段缺失时只跳过对应桶，另一边仍能展示
        let body = json!({
            "model_remains": [{
                "model_name": "general",
                "current_interval_remaining_percent": 60.0,
                "current_weekly_status": 1
            }]
        });
        let tiers = parse_minimax_tiers(&body);
        assert_eq!(tiers.len(), 1);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[0].used_percent, 40.0);
    }

    #[test]
    fn negative_percent_passes_through() {
        // 与智谱解析约定一致：负数 / 超 100 不裁剪
        let body = json!({
            "model_remains": [{
                "model_name": "general",
                "current_interval_remaining_percent": -5.0,
                "current_weekly_remaining_percent": 150.0,
                "current_interval_status": 1,
                "current_weekly_status": 1
            }]
        });
        let tiers = parse_minimax_tiers(&body);
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[0].used_percent, 105.0); // 100 - (-5)
        assert_eq!(tiers[1].used_percent, -50.0); // 100 - 150
    }

    #[test]
    fn weekly_status_3_skips_weekly_tier() {
        // 无周限额套餐：current_weekly_status=3，remaining_percent 恒为 100，
        // 不应产出 weekly tier（否则显示"0% 已用"的假周桶）
        let body = json!({
            "model_remains": [
                {
                    "model_name": "general",
                    "start_time": 1_780_347_600_000_i64,
                    "end_time": 1_780_365_600_000_i64,
                    "remains_time": 4_161_372_i64,
                    "current_interval_remaining_percent": 99,
                    "current_interval_status": 1,
                    "current_weekly_total_count": 0,
                    "current_weekly_usage_count": 0,
                    "weekly_start_time": 1_780_243_200_000_i64,
                    "weekly_end_time": 1_780_848_000_000_i64,
                    "weekly_remains_time": 486_561_372_i64,
                    "current_weekly_status": 3,
                    "current_weekly_remaining_percent": 100
                },
                {
                    "model_name": "video",
                    "current_interval_remaining_percent": 100,
                    "current_weekly_status": 3,
                    "current_weekly_remaining_percent": 100
                }
            ],
            "base_resp": { "status_code": 0, "status_msg": "success" }
        });
        let tiers = parse_minimax_tiers(&body);
        assert_eq!(tiers.len(), 1);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[0].used_percent, 1.0);
        assert!(tiers[0].resets_at.is_some());
    }

    #[test]
    fn weekly_status_2_also_skips_weekly_tier() {
        // 防御性：除 1 之外的 status 都视为周桶未激活
        let body = json!({
            "model_remains": [{
                "model_name": "general",
                "current_interval_remaining_percent": 80.0,
                "current_weekly_remaining_percent": 50.0,
                "current_weekly_status": 2
            }]
        });
        let tiers = parse_minimax_tiers(&body);
        assert_eq!(tiers.len(), 1);
        assert_eq!(tiers[0].window, WindowKind::FiveHour);
        assert_eq!(tiers[0].used_percent, 20.0);
    }
}
