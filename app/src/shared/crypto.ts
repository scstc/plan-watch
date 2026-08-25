/**
 * app↔server 接口加密（协议 v1），与 server 端 CryptoService 逐字节互通：
 * - RSA-OAEP(SHA-256) 包裹一次性 AES-256-GCM 密钥 → 请求 header `X-PW-Key`
 * - 业务 JSON 加密为 {"iv","data"} 信封（请求体与响应体同构）
 * WebCrypto 在 Tauri 2 三端 webview（WKWebView / WebView2 / WebKitGTK）均为
 * secure context，crypto.subtle 可用；不可用时显式报错而非静默失败。
 * 公钥信任采用 TOFU：首次从 GET /api/pubkey 获取并按服务端地址缓存于 localStorage，
 * 指纹变化时告警覆盖（核对入口：服务端启动日志 ↔ 设置页展示的指纹）。
 */

export interface ServerPublicKeyInfo {
  version: number;
  alg: string;
  fingerprint: string;
  publicKey: string;
}

/** 预留公钥指纹 pinning（填 64 位 hex 即开启校验）；空 = 仅 TOFU */
export const PINNED_FINGERPRINT = "";

const PUBKEY_CACHE_KEY = "pw-server-pubkey";

interface CachedKey {
  serverUrl: string;
  fingerprint: string;
  publicKey: string;
}

function subtle(): SubtleCrypto {
  const s = globalThis.crypto?.subtle;
  if (!s) {
    throw new Error("当前环境不支持 WebCrypto（crypto.subtle 不可用），无法启用接口加密");
  }
  return s;
}

export function b64(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}

/** 返回类型标注为 Uint8Array<ArrayBuffer> 以满足 BufferSource（TS 5.7+ 泛型化后） */
export function unb64(text: string): Uint8Array<ArrayBuffer> {
  const bin = atob(text);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function readCache(): CachedKey | null {
  try {
    const raw = localStorage.getItem(PUBKEY_CACHE_KEY);
    return raw ? (JSON.parse(raw) as CachedKey) : null;
  } catch {
    return null;
  }
}

function writeCache(info: CachedKey): void {
  try {
    localStorage.setItem(PUBKEY_CACHE_KEY, JSON.stringify(info));
  } catch {
    // localStorage 不可用（隐私模式等）：不缓存，每次请求重新获取公钥
  }
}

export function clearServerKeyCache(): void {
  try {
    localStorage.removeItem(PUBKEY_CACHE_KEY);
  } catch {
    // 同上，忽略
  }
}

/** 设置页 TOFU 校对入口：当前缓存的服务端公钥指纹（未连接过返回 null）。 */
export function cachedFingerprint(): string | null {
  return readCache()?.fingerprint ?? null;
}

async function importRsaKey(spkiBase64: string): Promise<CryptoKey> {
  return subtle().importKey(
    "spki",
    unb64(spkiBase64),
    { name: "RSA-OAEP", hash: "SHA-256" },
    false,
    ["encrypt"],
  );
}

/** 获取服务端公钥（TOFU 缓存；serverUrl 变化或 refresh 时重新拉取）。 */
export async function getServerKey(
  serverUrl: string,
  refresh = false,
): Promise<{ fingerprint: string; key: CryptoKey }> {
  let cached = readCache();
  if (refresh || !cached || cached.serverUrl !== serverUrl) {
    const resp = await fetch(`${serverUrl}/api/pubkey`);
    if (!resp.ok) {
      throw new Error(`获取服务端公钥失败: HTTP ${resp.status}`);
    }
    const info = (await resp.json()) as ServerPublicKeyInfo;
    if (info.version !== 1 || info.alg !== "RSA-OAEP-SHA256") {
      throw new Error(`服务端加密协议不兼容（version=${info.version}, alg=${info.alg}）`);
    }
    if (cached && cached.serverUrl === serverUrl && cached.fingerprint !== info.fingerprint) {
      console.warn(
        `服务端公钥已变化（${cached.fingerprint.slice(0, 16)}… → ${info.fingerprint.slice(0, 16)}…），已更新缓存`,
      );
    }
    cached = { serverUrl, fingerprint: info.fingerprint, publicKey: info.publicKey };
    writeCache(cached);
  }
  if (PINNED_FINGERPRINT && cached.fingerprint !== PINNED_FINGERPRINT) {
    throw new Error("服务端公钥指纹与固定值（PINNED_FINGERPRINT）不匹配，疑似中间人");
  }
  return { fingerprint: cached.fingerprint, key: await importRsaKey(cached.publicKey) };
}

export interface EncryptedRequest {
  /** 本次请求的一次性 AES 密钥（响应也用它解密） */
  aes: CryptoKey;
  headers: Record<string, string>;
  body?: string;
}

/** 客户端加密出口：生成一次性 AES-256 密钥，公钥包裹放 header；body 存在时加密为信封。 */
export async function buildEncryptedRequest(pub: CryptoKey, body?: string): Promise<EncryptedRequest> {
  const s = subtle();
  const aes = await s.generateKey({ name: "AES-GCM", length: 256 }, true, ["encrypt", "decrypt"]);
  const rawKey = await s.exportKey("raw", aes);
  const wrapped = await s.encrypt({ name: "RSA-OAEP" }, pub, rawKey);
  const headers: Record<string, string> = { "X-PW-Key": b64(new Uint8Array(wrapped)) };

  let envelope: string | undefined;
  if (body !== undefined) {
    const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await s.encrypt({ name: "AES-GCM", iv }, aes, new TextEncoder().encode(body));
    envelope = JSON.stringify({ iv: b64(iv), data: b64(new Uint8Array(ciphertext)) });
  }
  return { aes, headers, body: envelope };
}

/** 解响应信封；不是信封或解密失败返回 null（调用方按明文透传，兼容 required=false 的服务端）。 */
export async function openEnvelope(aes: CryptoKey, text: string): Promise<string | null> {
  if (!text.startsWith("{")) return null;
  let parsed: { iv?: unknown; data?: unknown };
  try {
    parsed = JSON.parse(text) as { iv?: unknown; data?: unknown };
  } catch {
    return null;
  }
  if (typeof parsed.iv !== "string" || typeof parsed.data !== "string") return null;
  try {
    const plain = await subtle().decrypt(
      { name: "AES-GCM", iv: unb64(parsed.iv) },
      aes,
      unb64(parsed.data),
    );
    return new TextDecoder().decode(plain);
  } catch {
    return null;
  }
}
