//! 统一额度数据模型与查询入口。
//!
//! 两个供应商的原始语义不同（MiniMax 给"剩余百分比"，智谱给"已用百分比"），
//! 解析层负责归一为 `used_percent`（已用 0–100）；窗口统一为 5 小时桶 / 周桶。
//! 错误三分类见 [`ErrorKind`]：网络失败保留上次成功数据（stale），其余确定性失败直接展示。

pub mod minimax;
pub mod zhipu;

use serde::Serialize;
use std::collections::HashMap;
use std::sync::OnceLock;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::config::{Account, ProviderKind};

// ── 数据类型 ──────────────────────────────────────────────

/// 单个限额窗口
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QuotaTier {
    pub window: WindowKind,
    /// 已用百分比 0–100（两个供应商统一为"已用"语义）
    pub used_percent: f64,
    /// ISO 8601 重置时间
    pub resets_at: Option<String>,
    /// 智谱积分套餐的绝对量：已用（currentValue）
    pub used: Option<f64>,
    /// 智谱积分套餐的绝对量：总额度（usage）
    pub total: Option<f64>,
    /// 智谱积分套餐的绝对量：剩余（remaining）
    pub remaining: Option<f64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum WindowKind {
    FiveHour,
    Weekly,
}

impl WindowKind {
    pub fn label(self) -> &'static str {
        match self {
            WindowKind::FiveHour => "5 小时",
            WindowKind::Weekly => "每周",
        }
    }
}

/// 单账号最近一次查询的状态快照
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountStatus {
    pub account_id: String,
    pub ok: bool,
    pub error: Option<AccountError>,
    /// 套餐档位（如智谱 lite）
    pub plan_level: Option<String>,
    pub tiers: Vec<QuotaTier>,
    /// 最近一次查询时间（ms epoch）
    pub queried_at: Option<i64>,
    /// 网络失败后沿用上次成功数据时为 true（此时 tiers 是旧数据）
    pub stale: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountError {
    pub kind: ErrorKind,
    pub message: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorKind {
    /// Key 无效 / 已过期 → 提醒用户换 Key
    Auth,
    /// 接口正常返回但业务失败（含解析失败）
    Business,
    /// 网络 / 超时 / 读体中断 → 瞬时失败，保留旧数据
    Network,
}

/// 单账号一次查询的归类结果。
/// 只有 `NetworkError` 是瞬时失败；其余都是确定性的，直接进状态快照。
#[derive(Debug)]
pub enum QueryOutcome {
    Success {
        plan_level: Option<String>,
        tiers: Vec<QuotaTier>,
    },
    AuthExpired(String),
    BusinessError(String),
    NetworkError(String),
}

impl QueryOutcome {
    pub fn into_status(self, account_id: &str) -> AccountStatus {
        let queried_at = Some(now_millis());
        let account_id = account_id.to_string();
        match self {
            QueryOutcome::Success { plan_level, tiers } => AccountStatus {
                account_id,
                ok: true,
                error: None,
                plan_level,
                tiers,
                queried_at,
                stale: false,
            },
            QueryOutcome::AuthExpired(message) => AccountStatus {
                account_id,
                ok: false,
                error: Some(AccountError { kind: ErrorKind::Auth, message }),
                plan_level: None,
                tiers: Vec::new(),
                queried_at,
                stale: false,
            },
            QueryOutcome::BusinessError(message) => AccountStatus {
                account_id,
                ok: false,
                error: Some(AccountError { kind: ErrorKind::Business, message }),
                plan_level: None,
                tiers: Vec::new(),
                queried_at,
                stale: false,
            },
            QueryOutcome::NetworkError(message) => AccountStatus {
                account_id,
                ok: false,
                error: Some(AccountError { kind: ErrorKind::Network, message }),
                plan_level: None,
                tiers: Vec::new(),
                queried_at,
                stale: true,
            },
        }
    }
}

/// 网络失败时合并旧数据：保留上次成功的 tiers / plan_level，标记 stale。
pub fn merge_network_failure(
    old: Option<&AccountStatus>,
    account_id: &str,
    message: String,
) -> AccountStatus {
    let mut status = QueryOutcome::NetworkError(message).into_status(account_id);
    if let Some(old) = old {
        status.tiers = old.tiers.clone();
        status.plan_level = old.plan_level.clone();
    }
    status
}

// ── 查询入口 ──────────────────────────────────────────────

pub async fn query_account(account: &Account) -> QueryOutcome {
    match account.provider {
        ProviderKind::Minimax => minimax::query(account.region, &account.api_key).await,
        ProviderKind::Zhipu => zhipu::query(account.region, &account.api_key).await,
    }
}

// ── 共享工具 ──────────────────────────────────────────────

/// 全局共享 HTTP 客户端（15s 总超时，rustls 免 OpenSSL 依赖）
pub(crate) fn http() -> &'static reqwest::Client {
    static HTTP: OnceLock<reqwest::Client> = OnceLock::new();
    HTTP.get_or_init(|| {
        reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .expect("failed to build http client")
    })
}

pub(crate) fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

/// 毫秒时间戳 → ISO 8601（UTC）
pub(crate) fn millis_to_iso8601(ms: i64) -> Option<String> {
    chrono::DateTime::from_timestamp(ms / 1000, ((ms % 1000) * 1_000_000) as u32)
        .map(|dt| dt.to_rfc3339())
}

/// 解析 JSON 数值，兼容数字与字符串两种形态
pub(crate) fn parse_f64(value: &serde_json::Value) -> Option<f64> {
    value
        .as_f64()
        .or_else(|| value.as_str().and_then(|s| s.parse().ok()))
}

/// 按字符数截断（防止网关 502 的整页 HTML 刷爆错误横幅）
pub(crate) fn truncate_chars(s: &str, max: usize) -> String {
    s.chars().take(max).collect()
}

/// 非 2xx 响应的统一分类（两个供应商共用）：
/// - 401/403 → 凭证失效（确定性）
/// - 429 / 5xx → 瞬时故障（限流/网关错误），走 NetworkError 保留上次成功数据
///   （cc-switch 前端 isTransientUsageError 同样把 5xx+429 归为瞬时）
/// - 其余 4xx → 业务层错误（确定性）
pub(crate) fn classify_http_error(status: reqwest::StatusCode, body: &str) -> QueryOutcome {
    if status == reqwest::StatusCode::UNAUTHORIZED || status == reqwest::StatusCode::FORBIDDEN {
        return QueryOutcome::AuthExpired(format!("Authentication failed (HTTP {status})"));
    }
    let body = truncate_chars(body, 200);
    if status.as_u16() == 429 || status.is_server_error() {
        QueryOutcome::NetworkError(format!("API error (HTTP {status}): {body}"))
    } else {
        QueryOutcome::BusinessError(format!("API error (HTTP {status}): {body}"))
    }
}

/// 把 `HashMap<id, status>` 序列化为按配置顺序排列的 Vec（前端好渲染）
pub fn statuses_ordered(
    map: &HashMap<String, AccountStatus>,
    accounts: &[Account],
) -> Vec<AccountStatus> {
    accounts
        .iter()
        .filter_map(|a| map.get(&a.id).cloned())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn status(code: u16) -> reqwest::StatusCode {
        reqwest::StatusCode::from_u16(code).unwrap()
    }

    #[test]
    fn classify_http_error_401_403_is_auth() {
        assert!(matches!(
            classify_http_error(status(401), ""),
            QueryOutcome::AuthExpired(_)
        ));
        assert!(matches!(
            classify_http_error(status(403), ""),
            QueryOutcome::AuthExpired(_)
        ));
    }

    #[test]
    fn classify_http_error_429_and_5xx_is_transient() {
        // 限流/网关错误是瞬时故障：走 NetworkError 让调度层保留上次成功数据
        assert!(matches!(
            classify_http_error(status(429), "slow down"),
            QueryOutcome::NetworkError(_)
        ));
        assert!(matches!(
            classify_http_error(status(502), "<html>bad gateway</html>"),
            QueryOutcome::NetworkError(_)
        ));
        assert!(matches!(
            classify_http_error(status(503), ""),
            QueryOutcome::NetworkError(_)
        ));
    }

    #[test]
    fn classify_http_error_other_4xx_is_business() {
        assert!(matches!(
            classify_http_error(status(400), "bad request"),
            QueryOutcome::BusinessError(msg) if msg.contains("bad request")
        ));
        assert!(matches!(
            classify_http_error(status(404), ""),
            QueryOutcome::BusinessError(_)
        ));
    }

    #[test]
    fn classify_http_error_truncates_body() {
        let long = "x".repeat(500);
        match classify_http_error(status(500), &long) {
            QueryOutcome::NetworkError(msg) => {
                assert!(msg.matches('x').count() == 200, "body should be truncated to 200 chars");
            }
            other => panic!("expected NetworkError, got {other:?}"),
        }
    }
}
