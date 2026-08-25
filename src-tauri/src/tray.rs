//! 托盘：菜单、tooltip、状态图标。全部 UI 文案中文。
//!
//! 菜单是"只读信息 + 少量动作"的扁平结构：
//! 头部刷新时间 → 各账号区块（名称 + 每窗口一行）→ 动作区（刷新/设置/退出）。

use std::collections::HashMap;

use tauri::menu::{Menu, MenuBuilder, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, Wry};
use tauri::image::Image;

use crate::config::{Account, AppConfig};
use crate::quota::{AccountStatus, ErrorKind, QuotaTier};
use crate::scheduler;
use crate::state::AppState;

pub const TRAY_ID: &str = "plan-watch";

/// 32×32 状态圆点（`tools/gen-icons.ps1` 生成，提交进仓库保证构建可用）
mod status_icons {
    pub const IDLE: &[u8] = include_bytes!("../icons/status/idle.png");
    pub const OK: &[u8] = include_bytes!("../icons/status/ok.png");
    pub const WARN: &[u8] = include_bytes!("../icons/status/warn.png");
    pub const CRIT: &[u8] = include_bytes!("../icons/status/crit.png");
}

enum IconState {
    Idle,
    Ok,
    Warn,
    Crit,
}

fn status_image(bytes: &[u8]) -> Option<Image<'_>> {
    Image::from_bytes(bytes).ok()
}

pub fn show_settings(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        // 最小化状态下 show() 是 no-op（tao 对 iconic 窗口跳过 visible diff），
        // 必须先还原再显示
        if win.is_minimized().unwrap_or(false) {
            let _ = win.unminimize();
        }
        let _ = win.show();
        let _ = win.set_focus();
        nudge_window(win);
    }
}

/// ±1px 伪 resize：webview 在窗口隐藏期间创建时，`show()` 后可能不重绘
/// （WebView2 停在白屏；WebKitGTK 也有类似失效模式，参考 cc-switch
/// `linux_fix.rs` / Tauri #10746）。一次肉眼不可见的 resize 逼浏览器
/// 重新布局绘制。fire-and-forget，不阻塞调用方。
fn nudge_window(window: tauri::WebviewWindow) {
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_millis(150)).await;
        if let Ok(original) = window.inner_size() {
            let bumped =
                tauri::PhysicalSize::new(original.width.saturating_add(1), original.height);
            let _ = window.set_size(bumped);
            tokio::time::sleep(std::time::Duration::from_millis(80)).await;
            let _ = window.set_size(original);
        }
    });
}

fn fmt_pct(p: f64) -> i64 {
    p.round() as i64
}

fn fmt_reset(iso: &str) -> String {
    chrono::DateTime::parse_from_rfc3339(iso)
        .map(|dt| {
            dt.with_timezone(&chrono::Local)
                .format("%m-%d %H:%M")
                .to_string()
        })
        .unwrap_or_else(|_| iso.to_string())
}

fn fmt_local_time(ms: i64) -> String {
    chrono::DateTime::from_timestamp(ms / 1000, 0)
        .map(|dt| {
            dt.with_timezone(&chrono::Local)
                .format("%H:%M:%S")
                .to_string()
        })
        .unwrap_or_default()
}

fn truncate(s: String, max_chars: usize) -> String {
    s.chars().take(max_chars).collect()
}

fn error_suffix(kind: ErrorKind) -> &'static str {
    match kind {
        ErrorKind::Auth => "⚠ 鉴权失败",
        ErrorKind::Business => "✖ 接口错误",
        ErrorKind::Network => "✖ 网络失败",
    }
}

fn account_header_label(a: &Account, st: Option<&AccountStatus>) -> String {
    let mut label = format!("{}（{} {}）", a.name, a.provider_label(), a.region_label());
    if !a.enabled {
        label.push_str(" · 已停用");
        return label;
    }
    match st {
        None => label.push_str(" · 等待刷新"),
        Some(s) => {
            if let Some(level) = &s.plan_level {
                label.push_str(&format!(" · {level}"));
            }
            if !s.ok {
                if let Some(err) = &s.error {
                    label.push(' ');
                    label.push_str(error_suffix(err.kind));
                }
            }
        }
    }
    label
}

fn tier_label(t: &QuotaTier) -> String {
    let mut s = format!(
        "　{}　已用 {}%",
        t.window.label(),
        fmt_pct(t.used_percent)
    );
    if let Some(reset) = &t.resets_at {
        s.push_str(&format!("　重置 {}", fmt_reset(reset)));
    }
    // 智谱积分套餐的绝对量
    if let Some(total) = t.total {
        s.push_str(&format!(
            "（{}/{}）",
            t.used.map(|v| v.round() as i64).unwrap_or(0),
            total.round() as i64
        ));
    }
    s
}

fn header_text(statuses: &HashMap<String, AccountStatus>) -> String {
    match statuses.values().filter_map(|s| s.queried_at).max() {
        Some(ms) => format!("plan-watch · 上次刷新 {}", fmt_local_time(ms)),
        None => "plan-watch".to_string(),
    }
}

fn build_menu_from(
    app: &AppHandle,
    config: &AppConfig,
    statuses: &HashMap<String, AccountStatus>,
) -> tauri::Result<Menu<Wry>> {
    let mut builder = MenuBuilder::new(app).item(&MenuItem::with_id(
        app,
        "header",
        header_text(statuses),
        false,
        None::<&str>,
    )?);

    if config.accounts.is_empty() {
        builder = builder.item(&MenuItem::with_id(
            app,
            "open_settings_empty",
            "暂无账号 — 点击打开设置添加",
            true,
            None::<&str>,
        )?);
    } else {
        for (i, account) in config.accounts.iter().enumerate() {
            builder = builder.separator().item(&MenuItem::with_id(
                app,
                format!("acc_{i}"),
                account_header_label(account, statuses.get(&account.id)),
                false,
                None::<&str>,
            )?);
            if !account.enabled {
                continue;
            }
            match statuses.get(&account.id) {
                Some(st) if !st.tiers.is_empty() => {
                    for (j, tier) in st.tiers.iter().enumerate() {
                        builder = builder.item(&MenuItem::with_id(
                            app,
                            format!("tier_{i}_{j}"),
                            tier_label(tier),
                            false,
                            None::<&str>,
                        )?);
                    }
                }
                Some(st) => {
                    // 无 tier：展示错误信息或占位
                    let detail = match &st.error {
                        Some(err) => format!("　{}", err.message),
                        None => "　暂无额度数据".to_string(),
                    };
                    builder = builder.item(&MenuItem::with_id(
                        app,
                        format!("detail_{i}"),
                        truncate(detail, 60),
                        false,
                        None::<&str>,
                    )?);
                }
                None => {}
            }
        }
    }

    builder = builder
        .separator()
        .item(&MenuItem::with_id(app, "refresh", "立即刷新", true, None::<&str>)?)
        .item(&MenuItem::with_id(app, "settings", "设置…", true, None::<&str>)?)
        .separator()
        .item(&MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?);

    builder.build()
}

/// tooltip 一行一个账号："名称 5h%/周%"。Windows szTip 上限 128 字符，超长截断。
fn build_tooltip(
    config: &AppConfig,
    statuses: &HashMap<String, AccountStatus>,
) -> String {
    let mut lines: Vec<String> = Vec::new();
    for a in config.accounts.iter().filter(|a| a.enabled) {
        let line = match statuses.get(&a.id) {
            Some(s) if !s.tiers.is_empty() => {
                let pcts: Vec<String> = s
                    .tiers
                    .iter()
                    .map(|t| format!("{}%", fmt_pct(t.used_percent)))
                    .collect();
                format!("{} {}", a.name, pcts.join("/"))
            }
            Some(s) => match &s.error {
                Some(e) => format!(
                    "{} {}",
                    a.name,
                    error_suffix(e.kind).trim_start_matches(['⚠', '✖', ' '])
                ),
                None => format!("{} …", a.name),
            },
            None => format!("{} …", a.name),
        };
        lines.push(line);
    }
    if lines.is_empty() {
        return "plan-watch".to_string();
    }
    lines.join("\n").chars().take(120).collect()
}

/// 图标状态取所有账号中最差的已用百分比：
/// 无数据 → 灰；≥90% → 红；≥阈值 → 黄；否则绿。确定性错误（鉴权/业务）不参与。
fn icon_state(config: &AppConfig, statuses: &HashMap<String, AccountStatus>) -> IconState {
    let mut worst: Option<f64> = None;
    for a in config.accounts.iter().filter(|a| a.enabled) {
        if let Some(st) = statuses.get(&a.id) {
            for t in &st.tiers {
                worst = Some(worst.map_or(t.used_percent, |w: f64| w.max(t.used_percent)));
            }
        }
    }
    match worst {
        None => IconState::Idle,
        Some(w) if w >= 90.0 => IconState::Crit,
        Some(w) if w >= f64::from(config.low_quota_threshold) => IconState::Warn,
        Some(_) => IconState::Ok,
    }
}

fn snapshot(app: &AppHandle) -> (AppConfig, HashMap<String, AccountStatus>) {
    let state = app.state::<AppState>();
    let config = state.config.read().unwrap().clone();
    let statuses = state.statuses.read().unwrap().clone();
    (config, statuses)
}

/// 菜单内容签名：只纳入真正影响菜单文案的字段。
/// `queried_at` 每轮刷新必变但只影响头部"上次刷新"时间——纳入会让
/// 防 set_menu 守卫完全失效（每轮都重建菜单，销毁用户正打开的菜单）。
/// 代价是菜单打开期间头部时间停留在旧值，可接受。
fn signature_source(
    config: &AppConfig,
    statuses: &HashMap<String, AccountStatus>,
) -> String {
    let mut st = statuses.clone();
    for s in st.values_mut() {
        s.queried_at = None;
    }
    format!("{config:?}{st:?}")
}

/// 状态变化后更新托盘（菜单 / tooltip / 图标）。
/// 菜单内容没变时跳过 set_menu，避免关掉用户正打开的菜单。
pub fn update(app: &AppHandle) {
    let Some(tray) = app.tray_by_id(TRAY_ID) else {
        return;
    };
    let (config, statuses) = snapshot(app);

    {
        let state = app.state::<AppState>();
        let mut sig = state.menu_signature.lock().unwrap();
        let new_sig = signature_source(&config, &statuses);
        if *sig != new_sig {
            if let Ok(menu) = build_menu_from(app, &config, &statuses) {
                let _ = tray.set_menu(Some(menu));
            }
            *sig = new_sig;
        }
    }

    let _ = tray.set_tooltip(Some(build_tooltip(&config, &statuses)));
    let bytes = match icon_state(&config, &statuses) {
        IconState::Idle => status_icons::IDLE,
        IconState::Ok => status_icons::OK,
        IconState::Warn => status_icons::WARN,
        IconState::Crit => status_icons::CRIT,
    };
    if let Some(img) = status_image(bytes) {
        let _ = tray.set_icon(Some(img));
    }
}

pub fn create(app: &AppHandle) -> tauri::Result<()> {
    let (config, statuses) = snapshot(app);
    let menu = build_menu_from(app, &config, &statuses)?;
    {
        let state = app.state::<AppState>();
        *state.menu_signature.lock().unwrap() = signature_source(&config, &statuses);
    }

    // 交互按平台惯例：
    // - Windows/Linux：左键单击打开设置，右键弹菜单（show_menu_on_left_click=false，
    //   且左键抬起不进模态菜单循环，双击误触「退出」的路径随之消失）
    // - macOS：左键弹菜单（菜单栏应用惯例）
    #[cfg(not(target_os = "macos"))]
    let show_menu_on_left = false;
    #[cfg(target_os = "macos")]
    let show_menu_on_left = true;

    let mut builder = TrayIconBuilder::with_id(TRAY_ID)
        .tooltip(build_tooltip(&config, &statuses))
        .menu(&menu)
        .show_menu_on_left_click(show_menu_on_left)
        .on_menu_event(|app, event| handle_menu_event(app, &event.id.0))
        .on_tray_icon_event(|tray, event| {
            #[cfg(not(target_os = "macos"))]
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_settings(tray.app_handle());
            }
            #[cfg(target_os = "macos")]
            {
                let _ = (tray, event);
            }
        });

    if let Some(icon) = status_image(status_icons::IDLE) {
        builder = builder.icon(icon);
    }

    builder.build(app)?;
    Ok(())
}

fn handle_menu_event(app: &AppHandle, id: &str) {
    match id {
        "refresh" => {
            tauri::async_runtime::spawn(scheduler::refresh_all(app.clone()));
        }
        "settings" | "open_settings_empty" => show_settings(app),
        "quit" => app.exit(0),
        _ => {}
    }
}
