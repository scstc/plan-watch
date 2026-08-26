//! 前端可调用的 Tauri commands。

use tauri::{AppHandle, Manager};

use crate::config::{Account, AppConfig};
use crate::quota::{self, AccountStatus, QueryOutcome};
use crate::scheduler;
use crate::state::AppState;
use crate::tray;

#[tauri::command]
pub fn get_config(app: AppHandle) -> AppConfig {
    let state = app.state::<AppState>();
    let cfg = state.config.read().unwrap().clone();
    cfg
}

/// 保存配置：规范化 → 先落盘（失败则内存不动，前端的"回滚拉取"才能拿到旧值）
/// → 提交内存 → 清理已删账号状态 → 刷新托盘 → 触发一次刷新。
#[tauri::command]
pub async fn save_config(app: AppHandle, config: AppConfig) -> Result<(), String> {
    let cfg = config.sanitized();
    {
        let state = app.state::<AppState>();
        cfg.save(&state.config_dir)?;

        let old_threshold = state.config.read().unwrap().low_quota_threshold;
        *state.config.write().unwrap() = cfg.clone();
        // 阈值变化 → 清空提醒标记，新阈值语义下重新走跨越检测
        // （否则旧标记会让"新阈值首次越线"的通知永不弹出）
        if old_threshold != cfg.low_quota_threshold {
            state.notified.lock().unwrap().clear();
        }

        let ids: std::collections::HashSet<String> =
            cfg.accounts.iter().map(|a| a.id.clone()).collect();
        state
            .statuses
            .write()
            .unwrap()
            .retain(|k, _| ids.contains(k));
        // notified 的 key 是 "{account_id}:{window}"
        state
            .notified
            .lock()
            .unwrap()
            .retain(|k| k.split(':').next().is_some_and(|id| ids.contains(id)));

        // 在途刷新据此丢弃旧配置的查询结果
        state.config_gen.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    }

    tray::update(&app);
    tauri::async_runtime::spawn(scheduler::refresh_all(app.clone()));
    Ok(())
}

#[tauri::command]
pub fn get_statuses(app: AppHandle) -> Vec<AccountStatus> {
    let state = app.state::<AppState>();
    let statuses = state.statuses.read().unwrap().clone();
    let accounts = state.config.read().unwrap().accounts.clone();
    quota::statuses_ordered(&statuses, &accounts)
}

/// 手动刷新（等待本轮完成再返回）。
#[tauri::command]
pub async fn refresh_now(app: AppHandle) {
    scheduler::refresh_all(app).await;
}

/// 用表单当前值即时测试一个账号（不写状态、不落盘）。
#[tauri::command]
pub async fn test_account(account: Account) -> AccountStatus {
    match quota::query_account(&account).await {
        QueryOutcome::NetworkError(msg) => {
            quota::merge_network_failure(None, &account.id, msg)
        }
        other => other.into_status(&account.id),
    }
}

/// 打开设置窗口（浮动列表 / 前端入口用）。
#[tauri::command]
pub fn open_settings(app: AppHandle) {
    tray::show_settings(&app);
}

/// 退出应用（浮动列表底部按钮）。
#[tauri::command]
pub fn quit_app(app: AppHandle) {
    app.exit(0);
}

/// 通用 HTTP 代理：前端用 invoke 调到 Rust 侧发起请求，绕过 WebView2
/// 在 production 模式下对明文跨网段 fetch 的限制。
/// 返回 (status, body_text, error_message)；任一为空表示该字段无值。
#[tauri::command]
pub async fn http_request(
    url: String,
    method: String,
    headers: std::collections::HashMap<String, String>,
    body: Option<String>,
) -> Result<HttpResponse, String> {
    use std::time::Duration;
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .map_err(|e| format!("build client: {e}"))?;
    let mut req = client.request(
        method.parse().map_err(|e| format!("bad method: {e}"))?,
        &url,
    );
    for (k, v) in &headers {
        req = req.header(k, v);
    }
    if let Some(b) = body {
        req = req.body(b);
    }
    let resp = req.send().await.map_err(|e| format!("send: {e}"))?;
    let status = resp.status().as_u16() as i32;
    let text = resp.text().await.map_err(|e| format!("read body: {e}"))?;
    Ok(HttpResponse { status, body: text })
}

#[derive(serde::Serialize)]
pub struct HttpResponse {
    pub status: i32,
    pub body: String,
}
