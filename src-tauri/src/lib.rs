//! plan-watch：任务栏里的 AI 编程套餐余额监控。
//!
//! 启动即最小化到托盘；设置窗口关闭时隐藏而非退出。

mod commands;
pub mod config;
pub mod quota;
mod scheduler;
mod state;
mod tray;

use tauri::{Manager, WindowEvent};

use config::AppConfig;
use state::AppState;

pub fn run() {
    tauri::Builder::default()
        // 单实例：二次启动只唤起已有实例的设置窗口（必须最先注册）
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            tray::show_settings(app);
        }))
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            // macOS：菜单栏常驻形态（不占 Dock / Cmd+Tab）；Windows 不受影响
            #[cfg(target_os = "macos")]
            app.set_activation_policy(tauri::ActivationPolicy::Accessory)?;

            let config_dir = app.path().app_config_dir()?;
            let config = AppConfig::load(&config_dir);
            let first_run = config.accounts.is_empty();

            app.manage(AppState::new(config, config_dir));
            tray::create(app.handle())?;
            scheduler::spawn(app.handle().clone());

            // 首次运行（还没有账号）直接弹设置窗口，引导配置
            if first_run {
                tray::show_settings(app.handle());
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            // 关闭设置窗口 → 隐藏到托盘继续监控（退出走托盘菜单）
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "main" {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_config,
            commands::save_config,
            commands::get_statuses,
            commands::refresh_now,
            commands::test_account,
        ])
        .run(tauri::generate_context!())
        .expect("error while running plan-watch");
}
