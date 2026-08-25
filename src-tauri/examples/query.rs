//! 冒烟工具：用真实 Key 走一遍查询链路（不含 GUI）。
//!
//! ```powershell
//! $env:PW_ZHIPU_KEY  = "…"; $env:PW_MINIMAX_KEY = "…"
//! cargo run --example query
//! ```

use plan_watch_lib::config::{Account, ProviderKind, Region};
use plan_watch_lib::quota;

fn account(provider: ProviderKind, key: &str) -> Account {
    Account {
        id: format!("{provider:?}"),
        name: format!("{provider:?}"),
        provider,
        region: Region::Cn,
        api_key: key.to_string(),
        enabled: true,
    }
}

#[tokio::main]
async fn main() {
    let mut accounts = Vec::new();
    if let Ok(key) = std::env::var("PW_ZHIPU_KEY") {
        accounts.push(account(ProviderKind::Zhipu, &key));
    }
    if let Ok(key) = std::env::var("PW_MINIMAX_KEY") {
        accounts.push(account(ProviderKind::Minimax, &key));
    }
    if accounts.is_empty() {
        eprintln!("set PW_ZHIPU_KEY / PW_MINIMAX_KEY first");
        std::process::exit(1);
    }

    for a in &accounts {
        let status = quota::query_account(a).await.into_status(&a.id);
        println!("=== {} ===", a.name);
        println!("{status:#?}");
    }
}
