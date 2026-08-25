//! 明文 JSON 配置：多供应商、多账号。
//!
//! 文件位于 Tauri `app_config_dir()`（Windows `%APPDATA%\com.planwatch.app\config.json`，
//! macOS `~/Library/Application Support/com.planwatch.app/config.json`）。
//! 密钥明文存储是 v0.x 的既定决策（与 cc-switch 一致），升级钥匙串是 v0.4+ 的事。

use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppConfig {
    /// 刷新间隔（秒），保存时钳制到 60..=86400
    #[serde(default = "default_refresh_interval_secs")]
    pub refresh_interval_secs: u64,
    /// 低额度提醒阈值（已用百分比），保存时钳制到 10..=99
    #[serde(default = "default_low_quota_threshold")]
    pub low_quota_threshold: u8,
    #[serde(default)]
    pub accounts: Vec<Account>,
}

fn default_refresh_interval_secs() -> u64 {
    300
}

fn default_low_quota_threshold() -> u8 {
    80
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            refresh_interval_secs: default_refresh_interval_secs(),
            low_quota_threshold: default_low_quota_threshold(),
            accounts: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    pub id: String,
    pub name: String,
    pub provider: ProviderKind,
    pub region: Region,
    pub api_key: String,
    #[serde(default = "default_true")]
    pub enabled: bool,
}

fn default_true() -> bool {
    true
}

impl Account {
    /// 菜单 / 横幅里展示的供应商标签
    pub fn provider_label(&self) -> &'static str {
        match self.provider {
            ProviderKind::Minimax => "MiniMax",
            ProviderKind::Zhipu => "智谱 GLM",
        }
    }

    pub fn region_label(&self) -> &'static str {
        match self.region {
            Region::Cn => "国内",
            Region::Global => "国际",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ProviderKind {
    Minimax,
    Zhipu,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Region {
    Cn,
    Global,
}

impl AppConfig {
    /// 读取配置；文件不存在或损坏时返回默认值。
    /// 损坏文件备份为 `config.json.corrupt`（诊断用；`config.json.bak` 专用于
    /// 保存上一份好配置，不能被坏文本覆盖）。
    pub fn load(dir: &Path) -> Self {
        let path = dir.join("config.json");
        match std::fs::read_to_string(&path) {
            Ok(text) => match serde_json::from_str(&text) {
                Ok(cfg) => cfg,
                Err(e) => {
                    eprintln!("config.json 解析失败（{e}），使用默认配置并备份原文件");
                    let _ = std::fs::write(dir.join("config.json.corrupt"), &text);
                    Self::default()
                }
            },
            Err(_) => Self::default(),
        }
    }

    pub fn save(&self, dir: &Path) -> Result<(), String> {
        std::fs::create_dir_all(dir).map_err(|e| format!("create config dir failed: {e}"))?;
        let text = serde_json::to_string_pretty(self)
            .map_err(|e| format!("serialize config failed: {e}"))?;
        let path = dir.join("config.json");
        // 保留上一份好配置：写坏/误删时用户还能从 .bak 恢复
        if path.exists() {
            let _ = std::fs::copy(&path, dir.join("config.json.bak"));
        }
        // 临时文件 + 原子替换：避免写一半进程退出留下截断 JSON
        let tmp = dir.join("config.json.tmp");
        std::fs::write(&tmp, &text).map_err(|e| format!("write config failed: {e}"))?;
        std::fs::rename(&tmp, &path).map_err(|e| format!("replace config failed: {e}"))
    }

    /// 入库前的规范化：钳制数值、补空 id、去重 id（保留首个）。
    pub fn sanitized(mut self) -> Self {
        self.refresh_interval_secs = self.refresh_interval_secs.clamp(60, 86_400);
        self.low_quota_threshold = self.low_quota_threshold.clamp(10, 99);
        let mut seen = std::collections::HashSet::new();
        let mut accounts = Vec::with_capacity(self.accounts.len());
        for mut account in self.accounts {
            if account.id.is_empty() {
                account.id = uuid::Uuid::new_v4().to_string();
            }
            if seen.insert(account.id.clone()) {
                accounts.push(account);
            }
        }
        self.accounts = accounts;
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> AppConfig {
        AppConfig {
            refresh_interval_secs: 120,
            low_quota_threshold: 75,
            accounts: vec![Account {
                id: "a1".into(),
                name: "智谱主力".into(),
                provider: ProviderKind::Zhipu,
                region: Region::Cn,
                api_key: "k".into(),
                enabled: true,
            }],
        }
    }

    #[test]
    fn roundtrip_preserves_fields() {
        let dir = tempfile::tempdir().unwrap();
        let cfg = sample();
        cfg.save(dir.path()).unwrap();
        let loaded = AppConfig::load(dir.path());
        assert_eq!(cfg, loaded);
    }

    #[test]
    fn load_missing_file_returns_default() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(AppConfig::load(dir.path()), AppConfig::default());
    }

    #[test]
    fn load_corrupt_file_backs_up_and_returns_default() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("config.json"), "{ not json").unwrap();
        assert_eq!(AppConfig::load(dir.path()), AppConfig::default());
        assert!(dir.path().join("config.json.corrupt").exists());
    }

    #[test]
    fn save_keeps_previous_good_version_in_bak() {
        // 二次保存时 .bak 应该是上一份（好）配置，而不是被覆盖丢失
        let dir = tempfile::tempdir().unwrap();
        let first = AppConfig {
            low_quota_threshold: 42,
            ..AppConfig::default()
        };
        first.save(dir.path()).unwrap();
        let second = AppConfig {
            low_quota_threshold: 77,
            ..AppConfig::default()
        };
        second.save(dir.path()).unwrap();
        let bak_text =
            std::fs::read_to_string(dir.path().join("config.json.bak")).unwrap();
        let bak: AppConfig = serde_json::from_str(&bak_text).unwrap();
        assert_eq!(bak.low_quota_threshold, 42);
        // 当前文件是新版，且没有 tmp 残留
        assert_eq!(AppConfig::load(dir.path()).low_quota_threshold, 77);
        assert!(!dir.path().join("config.json.tmp").exists());
    }

    #[test]
    fn load_partial_file_applies_defaults() {
        // 老版本配置缺少新字段时按默认值补齐，而不是解析失败
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(
            dir.path().join("config.json"),
            r#"{"accounts":[{"id":"x","name":"n","provider":"zhipu","region":"cn","apiKey":"k"}]}"#,
        )
        .unwrap();
        let cfg = AppConfig::load(dir.path());
        assert_eq!(cfg.refresh_interval_secs, 300);
        assert_eq!(cfg.low_quota_threshold, 80);
        assert_eq!(cfg.accounts.len(), 1);
        assert!(cfg.accounts[0].enabled); // 缺省 enabled=true
    }

    #[test]
    fn sanitized_clamps_and_dedups() {
        let cfg = AppConfig {
            refresh_interval_secs: 1,
            low_quota_threshold: 200,
            accounts: vec![
                Account {
                    id: "dup".into(),
                    name: "1".into(),
                    provider: ProviderKind::Minimax,
                    region: Region::Cn,
                    api_key: "k".into(),
                    enabled: true,
                },
                Account {
                    id: "dup".into(),
                    name: "2".into(),
                    provider: ProviderKind::Minimax,
                    region: Region::Cn,
                    api_key: "k".into(),
                    enabled: true,
                },
                Account {
                    id: String::new(),
                    name: "3".into(),
                    provider: ProviderKind::Minimax,
                    region: Region::Cn,
                    api_key: "k".into(),
                    enabled: true,
                },
            ],
        }
        .sanitized();
        assert_eq!(cfg.refresh_interval_secs, 60);
        assert_eq!(cfg.low_quota_threshold, 99);
        assert_eq!(cfg.accounts.len(), 2); // dup 去重；空 id 被补齐后保留
        assert!(!cfg.accounts[1].id.is_empty());
    }
}
