import { invoke } from "@tauri-apps/api/core";
import * as pwcrypto from "./crypto";
import type { Account, AccountStatus, AppConfig } from "./types";

/**
 * 数据源适配层：
 * - 本地模式（默认）：走 Tauri IPC（桌面端自己查询）
 * - 服务端模式：配置了后端接口地址后，全部数据走 HTTP（Spring Boot 服务端，
 *   接口与 Tauri commands 同构，见 server/）。请求经 crypto.ts 端到端加密
 *   （X-PW-Key + AES-GCM 信封，协议见 server CryptoService / README）。
 * 地址存在 localStorage（设备本地偏好，不随配置同步）。
 */
const SERVER_URL_KEY = "pw-server-url";

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

const inTauri = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

/**
 * 服务端模式的唯一 HTTP 出口：透明加解密（公钥获取在 http 之外，避免递归）。
 * - 请求：一次性 AES 密钥经服务端公钥包裹放 X-PW-Key，body 加密为信封
 * - 响应：信封解密；非信封按明文透传（服务端 required=false 兼容模式 / 网关错误页）
 * - 公钥轮换自愈：服务端换密钥后首次请求必报 PW_KEY_UNWRAP_FAILED，重取公钥重试一次
 */
async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const serverUrl = getServerUrl();
  let pub = (await pwcrypto.getServerKey(serverUrl)).key;
  for (let attempt = 0; ; attempt++) {
    const enc = await pwcrypto.buildEncryptedRequest(pub, init?.body as string | undefined);
    const resp = await fetch(serverUrl + path, {
      ...init,
      body: enc.body,
      headers: { "Content-Type": "application/json", ...enc.headers },
    });
    const text = await resp.text().catch(() => "");
    const plain = (await pwcrypto.openEnvelope(enc.aes, text)) ?? text;
    if (!resp.ok) {
      if (plain.includes("PW_KEY_UNWRAP_FAILED") && attempt === 0) {
        pwcrypto.clearServerKeyCache();
        pub = (await pwcrypto.getServerKey(serverUrl, true)).key;
        continue;
      }
      throw new Error(`HTTP ${resp.status}${plain ? `: ${plain.slice(0, 200)}` : ""}`);
    }
    return JSON.parse(plain) as T;
  }
}

export async function getConfig(): Promise<AppConfig> {
  if (isServerMode()) return http<AppConfig>("/api/config");
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
