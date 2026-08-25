#!/usr/bin/env node
/**
 * 生成 Java↔WebCrypto 互通测试向量（一次性脚本，产物已入库 webcrypto-vector.json）。
 *
 * 用 Node 内置 webcrypto（与浏览器/Tauri webview 的 WebCrypto 同规范实现）完全模拟
 * 前端客户端的加密行为：RSA-OAEP(SHA-256) 包裹一次性 AES-256 密钥 + AES-GCM 信封。
 * Java 侧 CryptoInteropVectorTest 用向量中的私钥解出 plaintext，即为互通的自动化证据。
 *
 * 重新生成：node gen-vector.mjs > webcrypto-vector.json
 */
import { webcrypto } from "node:crypto";

const subtle = webcrypto.subtle;
const b64 = (bytes) => Buffer.from(bytes).toString("base64");

function pem(der, label) {
  const body = Buffer.from(der)
    .toString("base64")
    .replace(/(.{64})/g, "$1\n")
    .trimEnd();
  return `-----BEGIN ${label}-----\n${body}\n-----END ${label}-----\n`;
}

const { privateKey, publicKey } = await subtle.generateKey(
  { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
  true,
  ["encrypt", "decrypt"],
);
const privateKeyPem = pem(await subtle.exportKey("pkcs8", privateKey), "PRIVATE KEY");
const publicKeySpki = new Uint8Array(await subtle.exportKey("spki", publicKey));

// 客户端行为：一次性 AES-256-GCM 密钥，用服务端公钥包裹
const aes = await subtle.generateKey({ name: "AES-GCM", length: 256 }, true, ["encrypt", "decrypt"]);
const rawKey = new Uint8Array(await subtle.exportKey("raw", aes));
const wrappedKey = new Uint8Array(await subtle.encrypt({ name: "RSA-OAEP" }, publicKey, rawKey));

// 业务明文 → AES-GCM 信封（12 字节 IV，密文 = ciphertext||tag）
const plaintext = JSON.stringify({
  refreshIntervalSecs: 300,
  lowQuotaThreshold: 20,
  accounts: [
    {
      id: "interop",
      name: "互通向量",
      provider: "minimax",
      region: "cn",
      apiKey: "sk-interop-vector",
      enabled: true,
    },
  ],
});
const iv = webcrypto.getRandomValues(new Uint8Array(12));
const ct = new Uint8Array(
  await subtle.encrypt({ name: "AES-GCM", iv }, aes, new TextEncoder().encode(plaintext)),
);

console.log(
  JSON.stringify(
    {
      note: "由 gen-vector.mjs 生成（Node webcrypto 模拟前端 WebCrypto 客户端）",
      privateKeyPem,
      publicKeySpki: b64(publicKeySpki),
      wrappedKey: b64(wrappedKey),
      envelope: { iv: b64(iv), data: b64(ct) },
      plaintext,
    },
    null,
    2,
  ),
);
