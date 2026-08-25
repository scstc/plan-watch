package com.planwatch.server.crypto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Set;

/**
 * app↔server 接口加密（协议 v1）：RSA-2048-OAEP(SHA-256) 包裹一次性 AES-256-GCM 密钥，
 * 业务 JSON 走 {"iv","data"} 信封。客户端用 WebCrypto（RSA-OAEP SHA-256 / AES-GCM），
 * 与本类逐字节互通。协议细节见 README「接口加密」。
 *
 * 注意：RSA 解包必须用 "RSA/ECB/OAEPPadding" + 显式 OAEPParameterSpec ——
 * SunJCE 的 "OAEPWithSHA-256AndMGF1Padding" 字符串默认 MGF1 用 SHA-1，与 WebCrypto 不一致。
 */
@Component
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    /** 信封 JSON：{"iv":"base64(12B)","data":"base64(ct||tag)"} */
    private record Envelope(String iv, String data) {}

    /** GET /api/pubkey 响应；version 留作后续算法协商（如升级 ECDH） */
    public record PubKeyInfo(int version, String alg, String fingerprint, String publicKey) {}

    static final int PROTOCOL_VERSION = 1;
    static final String ALG = "RSA-OAEP-SHA256";

    /** 见类注释：显式指定 OAEP 摘要与 MGF1 均为 SHA-256 */
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper mapper;
    private final Path keyFile;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String fingerprint;

    public CryptoService(@Value("${planwatch.data-dir}") String dataDir,
                         @Value("${planwatch.crypto.key-file:}") String keyFile,
                         ObjectMapper mapper) {
        this.mapper = mapper;
        this.keyFile = (keyFile == null || keyFile.isBlank())
                ? Path.of(dataDir).resolve("server.key")
                : Path.of(keyFile);
        KeyPair pair = loadOrCreate();
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
        this.fingerprint = sha256Hex(publicKey.getEncoded());
        log.info("接口加密就绪: RSA-2048 (协议 v1), 公钥指纹 {}, 密钥文件 {}", fingerprint, this.keyFile.toAbsolutePath());
    }

    public PubKeyInfo pubKeyInfo() {
        return new PubKeyInfo(PROTOCOL_VERSION, ALG, fingerprint,
                Base64.getEncoder().encodeToString(publicKey.getEncoded()));
    }

    /** RSA-OAEP 解开客户端用公钥包裹的 32 字节 AES-256 密钥（X-PW-Key header 值）。 */
    public SecretKey unwrapAesKey(String base64Wrapped) {
        try {
            byte[] wrapped = Base64.getDecoder().decode(base64Wrapped);
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP);
            byte[] raw = cipher.doFinal(wrapped);
            if (raw.length != 32) {
                throw new CryptoException("解包后的密钥长度异常: " + raw.length + "（期望 32 字节 AES-256）");
            }
            return new SecretKeySpec(raw, "AES");
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            // 公钥不匹配（服务端密钥已轮换）或数据损坏都会走到这里
            throw new CryptoException("AES 密钥解包失败（客户端需重新获取公钥）", e);
        }
    }

    /** 明文 JSON → 信封 JSON（随机 12B IV）。 */
    public String seal(SecretKey aes, String plaintextJson) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
            byte[] ctAndTag = cipher.doFinal(plaintextJson.getBytes(StandardCharsets.UTF_8));
            return mapper.writeValueAsString(new Envelope(
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(ctAndTag)));
        } catch (Exception e) {
            throw new CryptoException("响应加密失败", e);
        }
    }

    /** 信封 JSON → 明文 JSON。 */
    public String open(SecretKey aes, String envelopeJson) {
        try {
            JsonNode node = mapper.readTree(envelopeJson);
            JsonNode ivNode = node.get("iv");
            JsonNode dataNode = node.get("data");
            if (ivNode == null || dataNode == null || !ivNode.isTextual() || !dataNode.isTextual()) {
                throw new CryptoException("信封格式错误（缺少 iv/data）");
            }
            byte[] iv = Base64.getDecoder().decode(ivNode.asText());
            byte[] ctAndTag = Base64.getDecoder().decode(dataNode.asText());
            if (iv.length != 12) {
                throw new CryptoException("IV 长度异常: " + iv.length);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ctAndTag), StandardCharsets.UTF_8);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("请求信封解密失败", e);
        }
    }

    // ── 密钥文件 ──

    private KeyPair loadOrCreate() {
        if (Files.exists(keyFile)) {
            PrivateKey priv = readPrivateKey(keyFile);
            return new KeyPair(publicKeyOf(priv), priv);
        }
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            Files.writeString(keyFile, toPem(pair.getPrivate()), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(keyFile, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (Exception ignored) {
                // Windows 等非 POSIX 文件系统没有权限位
            }
            log.info("已生成服务端密钥对: {}", keyFile.toAbsolutePath());
            return pair;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("生成服务端密钥对失败: " + e.getMessage(), e);
        }
    }

    private PrivateKey readPrivateKey(Path file) {
        try {
            String pem = Files.readString(file, StandardCharsets.UTF_8);
            String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("读取密钥文件失败: " + file.toAbsolutePath(), e);
        }
    }

    /** PKCS#8 私钥通常带 CRT 参数，公钥可由模数+公指数重建；否则要求单独提供公钥文件。 */
    private PublicKey publicKeyOf(PrivateKey priv) {
        if (priv instanceof RSAPrivateCrtKey crt) {
            try {
                return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                        crt.getModulus(), crt.getPublicExponent()));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("重建公钥失败", e);
            }
        }
        throw new IllegalStateException("密钥文件缺少 CRT 参数，无法重建公钥（请用标准 RSA 密钥对生成）");
    }

    private static String toPem(PrivateKey key) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private static String sha256Hex(byte[] spki) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(spki);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("计算指纹失败", e);
        }
    }
}
