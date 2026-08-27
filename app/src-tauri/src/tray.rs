//! 托盘：tooltip、图标与交互。
//!
//! 交互约定：
//! - 左键：显示 / 隐藏浮动列表面板
//! - 右键：弹出菜单（显示浮动窗 / 打开设置 / 退出）
//! - 悬停：tooltip 一行一账号摘要
//!
//! 图标固定为品牌双色胶囊（左绿右红）；额度状态由浮动列表的
//! 状态点 / 渐变条与低额度通知表达，不占用托盘图标。
//! 全部 UI 文案中文。

use std::collections::HashMap;

use tauri::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, PhysicalPosition};
use tauri::image::Image;

use crate::config::AppConfig;
use crate::quota::{AccountStatus, ErrorKind};
use crate::state::AppState;
use crate::tray_util::nudge_window;

pub const TRAY_ID: &str = "plan-watch";

const MENU_ID_TOGGLE_FLOAT: &str = "toggle_float";
const MENU_ID_OPEN_SETTINGS: &str = "open_settings";
const MENU_ID_QUIT: &str = "quit";

/// 32×32 双色胶囊托盘图标（`tools/gen-icons.ps1` 生成）
mod status_icons {
    pub const DUAL: &[u8] = include_bytes!("../icons/status/dual.png");
}

fn tray_icon() -> Option<Image<'static>> {
    Image::from_bytes(status_icons::DUAL).ok()
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

/// 托盘左键：切换浮动列表面板的显示 / 隐藏。
fn toggle_float(app: &AppHandle) {
    let Some(win) = app.get_webview_window("float") else {
        return;
    };
    if win.is_visible().unwrap_or(false) {
        let _ = win.hide();
    } else {
        // 先贴合再显示，避免先闪在旧位置
        place_float(app);
        let _ = win.show();
        let _ = win.set_focus();
        // 隐藏期间创建的 webview 重新显示可能不重绘，踢一脚
        nudge_window(win);
    }
}

/// 把浮动列表贴着任务栏放置：以托盘图标为锚——图标在屏幕下半（Windows 底部
/// 任务栏）时窗口底边贴图标顶（≈任务栏上沿），水平居中于图标；图标在屏幕
/// 上半（macOS 菜单栏）时顶边贴图标底向下弹。取不到图标位置（Linux 等）则
/// 贴主屏右下角兜底。位置不持久化，每次启动 / 唤出都重新贴合（记忆的旧坐标
/// 在换屏 / 改分辨率后可能在屏幕外）。
pub fn place_float(app: &AppHandle) {
    let Some(win) = app.get_webview_window("float") else {
        return;
    };
    // 托盘图标在主屏任务栏 / 菜单栏，用主屏做 clamp 参照
    let Some(m) = win
        .primary_monitor()
        .ok()
        .flatten()
        .or_else(|| win.current_monitor().ok().flatten())
    else {
        return;
    };
    let (mx, my, mw, mh, scale) = {
        let p = m.position();
        let s = m.size();
        (p.x, p.y, s.width as i32, s.height as i32, m.scale_factor())
    };
    let (w, h) = match win.outer_size() {
        Ok(s) => (s.width as i32, s.height as i32),
        Err(_) => return,
    };

    let gap = 8;
    let anchor = app
        .tray_by_id(TRAY_ID)
        .and_then(|t| t.rect().ok().flatten())
        .map(|r| {
            let p = r.position.to_physical::<i32>(scale);
            let s = r.size.to_physical::<u32>(scale);
            (p.x, p.y, s.width as i32, s.height as i32)
        });

    let (x, y) = match anchor {
        // 图标在屏幕下半：向上弹，底边贴图标顶
        Some((tx, ty, tw, th)) if ty + th / 2 > my + mh / 2 => (tx + tw / 2 - w / 2, ty - h - gap),
        // 图标在屏幕上半：向下弹，顶边贴图标底
        Some((tx, ty, tw, th)) => (tx + tw / 2 - w / 2, ty + th + gap),
        // 取不到图标位置：贴主屏右下角兜底
        None => (mx + mw - w - 24, my + mh - h - 24),
    };

    // 图标贴屏幕边缘时把窗口拉回屏内
    let x = x.clamp(mx + gap, (mx + mw - w - gap).max(mx + gap));
    let y = y.clamp(my + gap, (my + mh - h - gap).max(my + gap));
    let _ = win.set_position(PhysicalPosition::new(x, y));
}

fn fmt_pct(p: f64) -> i64 {
    p.round() as i64
}

fn error_suffix(kind: ErrorKind) -> &'static str {
    match kind {
        ErrorKind::Auth => "鉴权失败",
        ErrorKind::Business => "接口错误",
        ErrorKind::Network => "网络失败",
    }
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
                // ∞ 窗口不占额度，tooltip 里跳过（避免出现误导性的"周 0%"）
                let pcts: Vec<String> = s
                    .tiers
                    .iter()
                    .filter(|t| !t.unlimited)
                    .map(|t| format!("{}%", fmt_pct(t.used_percent)))
                    .collect();
                format!("{} {}", a.name, pcts.join("/"))
            }
            Some(s) => match &s.error {
                Some(e) => format!("{} {}", a.name, error_suffix(e.kind)),
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

fn snapshot(app: &AppHandle) -> (AppConfig, HashMap<String, AccountStatus>) {
    let state = app.state::<AppState>();
    let config = state.config.read().unwrap().clone();
    let statuses = state.statuses.read().unwrap().clone();
    (config, statuses)
}

/// 状态变化后更新托盘（图标固定不变，只刷 tooltip）。
pub fn update(app: &AppHandle) {
    let Some(tray) = app.tray_by_id(TRAY_ID) else {
        return;
    };
    let (config, statuses) = snapshot(app);
    let _ = tray.set_tooltip(Some(build_tooltip(&config, &statuses)));
}

pub fn create(app: &AppHandle) -> tauri::Result<()> {
    let (config, statuses) = snapshot(app);

    // 右键菜单：显示浮动 / 打开设置 / 分隔 / 退出
    let menu = Menu::with_items(
        app,
        &[
            &MenuItem::with_id(
                app,
                MENU_ID_TOGGLE_FLOAT,
                "显示 / 隐藏浮动窗",
                true,
                None::<&str>,
            )?,
            &MenuItem::with_id(
                app,
                MENU_ID_OPEN_SETTINGS,
                "打开设置",
                true,
                None::<&str>,
            )?,
            &PredefinedMenuItem::separator(app)?,
            &MenuItem::with_id(app, MENU_ID_QUIT, "退出 plan-watch", true, None::<&str>)?,
        ],
    )?;

    let mut builder = TrayIconBuilder::with_id(TRAY_ID)
        .tooltip(build_tooltip(&config, &statuses))
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event: MenuEvent| match event.id().as_ref() {
            MENU_ID_TOGGLE_FLOAT => toggle_float(app),
            MENU_ID_OPEN_SETTINGS => show_settings(app),
            MENU_ID_QUIT => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                let app = tray.app_handle().clone();
                if matches!(button, MouseButton::Left) {
                    toggle_float(&app);
                }
                // 右键由 .menu() + show_menu_on_left_click(false) 自动弹出，无需在此处理
            }
        });

    if let Some(icon) = tray_icon() {
        builder = builder.icon(icon);
    }

    builder.build(app)?;
    Ok(())
}
