// 与 src-tauri/src/config.rs、quota/mod.rs 的数据结构一一对应（serde camelCase）

export type ProviderKind = "minimax" | "zhipu";
export type Region = "cn" | "global";

export interface Account {
  id: string;
  name: string;
  provider: ProviderKind;
  region: Region;
  apiKey: string;
  enabled: boolean;
}

export interface AppConfig {
  refreshIntervalSecs: number;
  lowQuotaThreshold: number;
  accounts: Account[];
}

export type WindowKind = "five_hour" | "weekly";
export type ErrorKind = "auth" | "business" | "network";

export interface QuotaTier {
  window: WindowKind;
  /** 已用百分比 0–100（两个供应商统一为”已用”语义） */
  usedPercent: number;
  /** ISO 8601 重置时间 */
  resetsAt: string | null;
  /** 智谱积分套餐的绝对量：已用 */
  used: number | null;
  /** 智谱积分套餐的绝对量：总额度 */
  total: number | null;
  /** 智谱积分套餐的绝对量：剩余 */
  remaining: number | null;
  /** 该窗口无限额（如 MiniMax 无周限额套餐），展示为 ∞ */
  unlimited: boolean;
}

export interface AccountError {
  kind: ErrorKind;
  message: string;
}

export interface AccountStatus {
  accountId: string;
  /** 最近一次查询是否成功 */
  ok: boolean;
  error: AccountError | null;
  /** 套餐档位（如智谱 lite） */
  planLevel: string | null;
  tiers: QuotaTier[];
  /** 最近一次查询时间（ms epoch） */
  queriedAt: number | null;
  /** 网络失败后沿用上次成功数据时为 true */
  stale: boolean;
}
