import { invoke } from "@tauri-apps/api/core";
import type { Account, AccountStatus, AppConfig } from "./types";

export function getConfig(): Promise<AppConfig> {
  return invoke<AppConfig>("get_config");
}

export function saveConfig(config: AppConfig): Promise<void> {
  return invoke<void>("save_config", { config });
}

export function getStatuses(): Promise<AccountStatus[]> {
  return invoke<AccountStatus[]>("get_statuses");
}

/** 立即刷新所有已启用账号（后台异步执行，结果经 status-updated 事件推送） */
export function refreshNow(): Promise<void> {
  return invoke<void>("refresh_now");
}

/** 用表单当前值即时测试一个账号（无需先保存） */
export function testAccount(account: Account): Promise<AccountStatus> {
  return invoke<AccountStatus>("test_account", { account });
}
