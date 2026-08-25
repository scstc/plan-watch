//! plan-watch：任务栏里的 AI 编程套餐余额监控。
//!
//! 启动即最小化到托盘；设置窗口关闭时隐藏而非退出。

mod commands;
pub mod config;
pub mod quota;
mod scheduler;
mod state;
mod tray;
mod tray_util;

use tauri::{Manager, WindowEvent};

use config::AppConfig;
use state::AppState;

/// 在 setup（manage 之后）创建全部窗口：
/// - main：设置页（隐藏，托盘右键打开）
/// - float：浮动额度列表（常驻显示、置顶、可拖动；托盘左键切换显隐，
///   位置由 window-state 插件记忆，高度随账号数自适应）
fn create_windows(app: &mut tauri::App) -> tauri::Result<()> {
    use tauri::{WebviewUrl, WebviewWindowBuilder};

    let _main = WebviewWindowBuilder::new(app, "main", WebviewUrl::default())
        .title("plan-watch 设置")
        .inner_size(760.0, 680.0)
        .min_inner_size(640.0, 520.0)
        .visible(false)
        .center()
        .resizable(true)
        .build()?;

    let _float = WebviewWindowBuilder::new(app, "float", WebviewUrl::default())
        .title("plan-watch")
        .inner_size(232.0, 170.0)
        .decorations(false)
        .skip_taskbar(true)
        .always_on_top(true)
        .resizable(false)
        .visible(true)
        .focused(false)
        .build()?;

    Ok(())
}

pub fn run() {
    tauri::Builder::default()
        // 单实例：二次启动只唤起已有实例的设置窗口（必须最先注册）
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            tray::show_settings(app);
        }))
        .plugin(tauri_plugin_notification::init())
        // 记住窗口位置（浮动列表拖到哪，重启还在哪）；只记位置不记尺寸，
        // 尺寸由前端按账号数量自适应
        .plugin(
            tauri_plugin_window_state::Builder::default()
                .with_state_flags(tauri_plugin_window_state::StateFlags::POSITION)
                .build(),
        )
        .setup(|app| {
            // macOS：菜单栏常驻形态（不占 Dock / Cmd+Tab）；Windows 不受影响。
            // 注意此 API 在 macOS 返回 ()，不要加 `?`
            #[cfg(target_os = "macos")]
            app.set_activation_policy(tauri::ActivationPolicy::Accessory);

            let config_dir = app.path().app_config_dir()?;
            let config = AppConfig::load(&config_dir);
            let first_run = config.accounts.is_empty();

            // 窗口必须在 manage() 之后创建：conf 声明的窗口会先于 setup 加载页面，
            // 悬浮球（visible）一启动就 invoke，会赶在状态注册前触发 state() panic
            app.manage(AppState::new(config, config_dir));
            create_windows(app)?;

            tray::create(app.handle())?;
            scheduler::spawn(app.handle().clone());

            // 首次运行（还没有账号）直接弹设置窗口，引导配置
            if first_run {
                tray::show_settings(app.handle());
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            match event {
                // 关闭 → 隐藏（main 回托盘；float 等托盘左键再唤出）
                WindowEvent::CloseRequested { api, .. }
                    if matches!(window.label(), "main" | "float") =>
                {
                    api.prevent_close();
                    let _ = window.hide();
                }
                _ => {}
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_config,
            commands::save_config,
            commands::get_statuses,
            commands::refresh_now,
            commands::test_account,
            commands::open_settings,
            commands::quit_app,
        ])
        .run(tauri::generate_context!())
        .expect("error while running plan-watch");
}
