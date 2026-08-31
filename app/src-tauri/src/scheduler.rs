//! 后台定时刷新与低额度提醒。
//!
//! 刷新策略：
//! - 定时循环每轮重读 interval（改配置下一轮生效）
//! - 手动刷新与定时刷新并发时合并为一次（`refreshing` 重入保护）
//! - 网络失败保留上次成功数据（stale），确定性失败直接展示错误

use std::collections::HashMap;
use std::sync::atomic::Ordering;
use std::time::Duration;

use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_notification::NotificationExt;

use crate::config::Account;
use crate::quota::{self, AccountStatus, QueryOutcome, WindowKind};
use crate::state::AppState;
use crate::tray;

/// 启动定时刷新循环。
pub fn spawn(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        loop {
            refresh_all(app.clone()).await;
            let secs = app
                .state::<AppState>()
                .config
                .read()
                .unwrap()
                .refresh_interval_secs
                .clamp(60, 86_400);
            tokio::time::sleep(Duration::from_secs(secs)).await;
        }
    });
}

/// 刷新全部已启用账号并更新托盘 / 通知前端。
/// 并发调用合并为一次；在途期间若配置被保存（config_gen 变化）或又有刷新
/// 请求到达，本轮结果会被丢弃并立即补跑一轮——保证"保存新 Key 后看到
/// 的一定是新 Key 的查询结果"。
pub async fn refresh_all(app: AppHandle) {
    {
        let state = app.state::<AppState>();
        if state.refreshing.swap(true, Ordering::SeqCst) {
            state.refresh_dirty.store(true, Ordering::SeqCst);
            return;
        }
    }
    loop {
        // true = 本轮期间配置已变（或期间有新请求），结果作废，立即重跑
        let rerun = refresh_inner(&app).await;
        let state = app.state::<AppState>();
        if rerun || state.refresh_dirty.swap(false, Ordering::SeqCst) {
            continue;
        }
        state.refreshing.store(false, Ordering::SeqCst);
        return;
    }
}

/// 返回 true 表示查询期间配置已变，本轮结果应丢弃。
async fn refresh_inner(app: &AppHandle) -> bool {
    let (accounts, threshold, gen) = {
        let state = app.state::<AppState>();
        let cfg = state.config.read().unwrap();
        (
            cfg.accounts.clone(),
            f64::from(cfg.low_quota_threshold),
            state.config_gen.load(Ordering::SeqCst),
        )
    };
    let enabled: Vec<&Account> = accounts.iter().filter(|a| a.enabled).collect();

    if enabled.is_empty() {
        // 没有启用账号也要同步托盘与前端（比如刚删光账号）
        emit_statuses(app, &accounts);
        tray::update(app);
        return false;
    }

    // 判断"是否首轮查询"用：有 prior 的账号越线不弹通知（避免每次启动轰炸）
    let prior: HashMap<String, AccountStatus> = app
        .state::<AppState>()
        .statuses
        .read()
        .unwrap()
        .clone();

    let outcomes = futures::future::join_all(enabled.iter().map(|a| quota::query_account(a))).await;

    // (account, status, had_prior)
    let mut updated: Vec<(Account, AccountStatus, bool)> = Vec::with_capacity(enabled.len());
    for (account, outcome) in enabled.into_iter().zip(outcomes) {
        let had_prior = prior.contains_key(&account.id);
        let status = match outcome {
            QueryOutcome::NetworkError(msg) => {
                quota::merge_network_failure(prior.get(&account.id), &account.id, msg)
            }
            other => other.into_status(&account.id),
        };
        updated.push((account.clone(), status, had_prior));
    }

    {
        let state = app.state::<AppState>();
        // 写状态前核对配置代数：查询期间 save_config 发生过 → 丢弃本轮（外层立即重跑）
        if state.config_gen.load(Ordering::SeqCst) != gen {
            return true;
        }
        for (account, status, had_prior) in &updated {
            check_low_quota(&state, app, account, status, threshold, *had_prior);
        }
        let mut statuses = state.statuses.write().unwrap();
        for (account, status, _) in &updated {
            statuses.insert(account.id.clone(), status.clone());
        }
    }

    emit_statuses(app, &accounts);
    tray::update(app);
    false
}

fn emit_statuses(app: &AppHandle, accounts: &[Account]) {
    let list = {
        let state = app.state::<AppState>();
        let statuses = state.statuses.read().unwrap().clone();
        quota::statuses_ordered(&statuses, accounts)
    };
    let _ = app.emit("status-updated", list);
}

/// 低额度跨越检测：仅在"上一轮还在阈值下、这一轮越线"时通知一次；
/// 回落到阈值 − 5pt 以下重置标记（迟滞），避免在阈值附近抖动反复弹通知。
/// 确定性失败（鉴权/业务）清掉标记——恢复或换 Key 后重新走跨越逻辑。
fn check_low_quota(
    state: &AppState,
    app: &AppHandle,
    account: &Account,
    status: &AccountStatus,
    threshold: f64,
    had_prior: bool,
) {
    let mut to_notify: Vec<WindowKind> = Vec::new();
    {
        let mut notified = state.notified.lock().unwrap();
        if !status.ok && status.tiers.is_empty() {
            let prefix = format!("{}:", account.id);
            notified.retain(|k| !k.starts_with(&prefix));
            return;
        }
        for t in &status.tiers {
            let key = format!("{}:{:?}", account.id, t.window);
            if t.used_percent >= threshold {
                if notified.insert(key) && had_prior {
                    to_notify.push(t.window);
                }
                // 首轮查询已越线（had_prior == false）只记录不通知
            } else if t.used_percent < threshold - 5.0 {
                notified.remove(&key);
            }
        }
    }
    for window in to_notify {
        let pct = status
            .tiers
            .iter()
            .find(|t| t.window == window)
            .map(|t| t.used_percent)
            .unwrap_or(0.0);
        // 不能静默吞错：macOS 未公证产物上通知链路可能整体失效（如
        // LaunchServices 未注册 bundle id），无日志将完全无从排查
        if let Err(e) = app
            .notification()
            .builder()
            .title("plan-watch 低额度提醒")
            .body(format!(
                "「{}」{}窗口已用 {}%（阈值 {}%）",
                account.name,
                window.label(),
                pct.round() as i64,
                threshold.round() as i64
            ))
            .show()
        {
            eprintln!("[plan-watch] 低额度通知发送失败: {e}");
        }
    }
}
