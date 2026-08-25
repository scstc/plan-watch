//! 窗口显示辅助：跨平台"踢一脚"。

use std::time::Duration;

use tauri::WebviewWindow;

/// ±1px 伪 resize：webview 在窗口隐藏期间创建时，`show()` 后可能不重绘
/// （WebView2 停在白屏；WebKitGTK 也有类似失效模式，参考 cc-switch
/// `linux_fix.rs` / Tauri #10746）。一次肉眼不可见的 resize 逼浏览器
/// 重新布局绘制。fire-and-forget，不阻塞调用方。
pub fn nudge_window(window: WebviewWindow) {
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_millis(150)).await;
        if let Ok(original) = window.inner_size() {
            let bumped =
                tauri::PhysicalSize::new(original.width.saturating_add(1), original.height);
            let _ = window.set_size(bumped);
            tokio::time::sleep(Duration::from_millis(80)).await;
            let _ = window.set_size(original);
        }
    });
}
