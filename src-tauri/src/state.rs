//! 应用共享状态（Tauri managed）。
//!
//! 包一层 `Arc<Inner>`：`State<'_, _>` 的生命周期挂在 app 上，
//! 想跨 `.await` 持有状态时 clone 内部 Arc 即可。

use std::collections::{HashMap, HashSet};
use std::ops::Deref;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64};
use std::sync::{Arc, Mutex, RwLock};

use crate::config::AppConfig;
use crate::quota::AccountStatus;

pub struct AppState(pub Arc<Inner>);

pub struct Inner {
    pub config: RwLock<AppConfig>,
    pub statuses: RwLock<HashMap<String, AccountStatus>>,
    pub config_dir: PathBuf,
    /// 低额度提醒去重：`"{account_id}:{window}"`，带迟滞回落
    pub notified: Mutex<HashSet<String>>,
    /// 刷新重入保护（定时与手动并发时合并为一次）
    pub refreshing: AtomicBool,
    /// 在途刷新期间又来了刷新请求（如 save_config）→ 本轮结束后立即补跑
    pub refresh_dirty: AtomicBool,
    /// 配置代数：save_config 每次提交 +1。在途刷新据此丢弃过期结果，
    /// 避免"保存了新 Key，却在途的旧 Key 查询结果反过来覆写状态"
    pub config_gen: AtomicU64,
    /// 上次托盘菜单内容签名（内容不变则跳过 set_menu，
    /// 避免刷新时关掉用户正打开的菜单）
    pub menu_signature: Mutex<String>,
}

impl AppState {
    pub fn new(config: AppConfig, config_dir: PathBuf) -> Self {
        Self(Arc::new(Inner {
            config: RwLock::new(config),
            statuses: RwLock::new(HashMap::new()),
            config_dir,
            notified: Mutex::new(HashSet::new()),
            refreshing: AtomicBool::new(false),
            refresh_dirty: AtomicBool::new(false),
            config_gen: AtomicU64::new(0),
            menu_signature: Mutex::new(String::new()),
        }))
    }
}

impl Deref for AppState {
    type Target = Inner;

    fn deref(&self) -> &Inner {
        &self.0
    }
}
