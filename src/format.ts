import type { ProviderKind, Region } from "./types";

const pad = (n: number) => String(n).padStart(2, "0");

/** 47.0 → "47%"（四舍五入，避免小数抖动） */
export function fmtPercent(x: number): string {
  return `${Math.round(x)}%`;
}

/** ISO 8601 → 本地 "MM-dd HH:mm" */
export function fmtReset(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** ms epoch → 本地 "HH:mm:ss" */
export function fmtClock(ms: number | null): string {
  if (ms == null) return "";
  const d = new Date(ms);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** 距重置的剩余时长：45m / 3.2h / 2d4h；缺失/已过期/非法返回 null */
export function fmtCountdown(iso: string | null): string | null {
  if (!iso) return null;
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return null;
  const ms = t - Date.now();
  if (ms <= 0) return "0m";
  const mins = Math.floor(ms / 60_000);
  if (mins < 60) return `${mins}m`;
  const hours = ms / 3_600_000;
  if (hours < 48) return `${hours >= 10 ? Math.round(hours) : hours.toFixed(1)}h`;
  const days = Math.floor(hours / 24);
  const remH = Math.round(hours % 24);
  return remH > 0 ? `${days}d${remH}h` : `${days}d`;
}

/** 千分位整数 */
export function fmtNum(x: number): string {
  return Math.round(x).toLocaleString("zh-CN");
}

/** 密钥脱敏：保留头 5 位与尾 4 位 */
export function maskKey(key: string): string {
  if (!key) return "";
  if (key.length <= 10) return "••••••";
  return `${key.slice(0, 5)}…${key.slice(-4)}`;
}

export const PROVIDER_LABEL: Record<ProviderKind, string> = {
  minimax: "MiniMax",
  zhipu: "智谱 GLM",
};

export const REGION_LABEL: Record<Region, string> = {
  cn: "国内",
  global: "国际",
};

export const ERROR_KIND_LABEL: Record<string, string> = {
  auth: "鉴权失败",
  business: "接口错误",
  network: "网络失败",
};
