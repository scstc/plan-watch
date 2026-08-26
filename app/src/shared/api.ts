import { invoke } from "@tauri-apps/api/core";
import type { Account, AccountStatus, AppConfig } from "./types";

/**
 * 数据源适配层：
 * - 本地模式（默认）：走 Tauri IPC（桌面端自己查询）
 * - 服务端模式：配置了后端接口地址后，全部数据走 HTTP（Spring Boot 服务端，
 *   接口与 Tauri commands 同构，见 server/）。鉴权用 Bearer token（从服务端
 *   /api/pair 拿），协议见 server AuthService / README。
 * 地址与 token 都在 localStorage（设备本地偏好，不随配置同步）。
 */
const SERVER_URL_KEY = "pw-server-url";
/** 已签发的 bearer token，按服务端地址分桶；切换地址即换独立身份 */
const tokenKey = (url: string) => `pw-token:${url}`;

export function getServerUrl(): string {
  return (localStorage.getItem(SERVER_URL_KEY) ?? "").trim().replace(/\/+$/, "");
}

export function setServerUrl(url: string): void {
  const trimmed = url.trim();
  if (trimmed) {
    localStorage.setItem(SERVER_URL_KEY, trimmed);
  } else {
    localStorage.removeItem(SERVER_URL_KEY);
  }
}

export function isServerMode(): boolean {
  return getServerUrl() !== "";
}

/** 当前 URL 下是否已签发并存储了 bearer token（仅用于 UI 提示，非安全判断）。 */
export function hasToken(): boolean {
  return localStorage.getItem(tokenKey(getServerUrl())) !== null;
}

function getToken(): string | null {
  return localStorage.getItem(tokenKey(getServerUrl()));
}

function setToken(token: string): void {
  localStorage.setItem(tokenKey(getServerUrl()), token);
}

function clearToken(): void {
  localStorage.removeItem(tokenKey(getServerUrl()));
}

const inTauri = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

/**
 * 服务端模式的唯一 HTTP 出口：每个请求带 Authorization: Bearer <token>。
 * 服务端换 / 吊销 token 后首次请求返回 401 PW_AUTH_REQUIRED → 清本地 token
 * 并抛带指引的错误，由 UI 引导用户回到「设备配对」。
 */
async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const serverUrl = getServerUrl();
  const token = getToken();
  if (!token) {
    throw new Error("设备未配对：请到 设置→通用设置「设备配对」输入服务端启动日志中的配对码");
  }
  const resp = await fetch(serverUrl + path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      ...(init?.headers ?? {}),
    },
  });
  const text = await resp.text().catch(() => "");
  if (!resp.ok) {
    if (resp.status === 401 && text.includes("PW_AUTH_REQUIRED")) {
      clearToken();
      throw new Error("设备未配对：请到 设置→通用设置「设备配对」输入服务端启动日志中的配对码");
    }
    throw new Error(`HTTP ${resp.status}${text ? `: ${text.slice(0, 200)}` : ""}`);
  }
  if (text.startsWith("<")) {
    // 典型场景：后端地址误填成了网页（vite/网关返回 HTML），提前给出可读错误
    throw new Error("服务端返回的不是 JSON（后端接口地址疑似填错，当前指向网页）");
  }
  return JSON.parse(text) as T;
}

/** 设备配对：提交配对码 → 拿到 bearer → 持久化。 */
export async function pairServer(code: string): Promise<void> {
  const serverUrl = getServerUrl();
  // 配对码按 UI 视觉格式（1234-5678）发送；服务端也会自行去 dash
  const dashed = code.trim().replace(/^(\d{4})(\d{4})$/, "$1-$2");
  const resp = await fetch(serverUrl + "/api/pair", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      code: dashed,
      name: `plan-watch@${navigator.platform || "desktop"}`,
    }),
  });
  const text = await resp.text().catch(() => "");
  if (!resp.ok) {
    if (text.includes("PW_PAIR_LOCKED")) {
      throw new Error("配对尝试过于频繁，服务端已临时锁定，请稍后再试");
    }
    if (text.includes("PW_PAIR_BAD")) {
      throw new Error("配对码错误（请核对服务端启动日志中的 8 位数字）");
    }
    throw new Error(`HTTP ${resp.status}${text ? `: ${text.slice(0, 200)}` : ""}`);
  }
  const { token } = JSON.parse(text) as { token: string };
  setToken(token);
}

export async function getConfig(): Promise<AppConfig> {
  if (isServerMode()) return http<AppConfig>("/api/config");
  return getConfigLocal();
}

/** 本地模式直取（Tauri IPC）；服务端未配对时作为设置页的兜底渲染基底 */
export async function getConfigLocal(): Promise<AppConfig> {
  return invoke<AppConfig>("get_config");
}

/** 保存配置；服务端模式返回服务端规范化后的配置 */
export async function saveConfig(config: AppConfig): Promise<AppConfig | void> {
  if (isServerMode()) {
    return http<AppConfig>("/api/config", {
      method: "PUT",
      body: JSON.stringify(config),
    });
  }
  return invoke<void>("save_config", { config });
}

export async function getStatuses(): Promise<AccountStatus[]> {
  if (isServerMode()) return http<AccountStatus[]>("/api/statuses");
  return invoke<AccountStatus[]>("get_statuses");
}

/** 立即刷新（本地模式后台异步；服务端模式等刷新完成） */
export async function refreshNow(): Promise<void> {
  if (isServerMode()) {
    await http<AccountStatus[]>("/api/refresh", { method: "POST" });
    return;
  }
  return invoke<void>("refresh_now");
}

/** 用表单当前值即时测试一个账号（无需先保存） */
export async function testAccount(account: Account): Promise<AccountStatus> {
  if (isServerMode()) {
    return http<AccountStatus>("/api/test", {
      method: "POST",
      body: JSON.stringify(account),
    });
  }
  return invoke<AccountStatus>("test_account", { account });
}

/** 打开设置窗口（仅 Tauri 内有意义） */
export async function openSettings(): Promise<void> {
  if (inTauri) return invoke<void>("open_settings");
}

/** 退出应用（仅 Tauri 内有意义） */
export async function quitApp(): Promise<void> {
  if (inTauri) return invoke<void>("quit_app");
}
