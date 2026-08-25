package com.planwatch.server.crypto;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加解密核心的单测。互通性（Java↔WebCrypto）另见 CryptoInteropVectorTest；
 * 这里验证：信封往返、坏数据报错、密钥文件复用、公钥指纹自洽。
 */
class CryptoServiceTest {

    /** 与 WebCrypto RSA-OAEP-SHA256 一致的显式参数（MGF1 也必须是 SHA-256） */
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private CryptoService service() {
        return new CryptoService(tempDir.toString(), "", mapper);
    }

    private static SecretKey aesKey() throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        return gen.generateKey();
    }

    @Test
    void sealOpenRoundTrips() throws Exception {
        CryptoService svc = service();
        SecretKey aes = aesKey();
        String json = """
                {"accounts":[{"id":"a1","apiKey":"sk-secret"}],"refreshIntervalSecs":300}""";
        String envelope = svc.seal(aes, json);
        // 信封必须是 {"iv","data"} 且不含明文
        assertThat(envelope).contains("\"iv\"").contains("\"data\"").doesNotContain("sk-secret");
        assertThat(svc.open(aes, envelope)).isEqualTo(json);
    }

    @Test
    void openRejectsMalformedEnvelope() throws Exception {
        CryptoService svc = service();
        SecretKey aes = aesKey();
        assertThatThrownBy(() -> svc.open(aes, "not-json"))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> svc.open(aes, "{\"foo\":1}"))
                .isInstanceOf(CryptoException.class);
        // 合法信封但用别的密钥解 → GCM 校验失败
        String envelope = svc.seal(aesKey(), "{\"a\":1}");
        assertThatThrownBy(() -> svc.open(aes, envelope))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    void unwrapRejectsGarbage() {
        byte[] garbage = new byte[256];
        new Random(42).nextBytes(garbage);
        assertThatThrownBy(() -> service().unwrapAesKey(Base64.getEncoder().encodeToString(garbage)))
                .isInstanceOf(CryptoException.class);
    }

    /** 用公钥包裹 → 服务端解开 → 密钥可用：自证 unwrap 与客户端包裹逻辑配对。 */
    @Test
    void unwrapRecoversClientWrappedKey() throws Exception {
        CryptoService svc = service();
        CryptoService.PubKeyInfo info = svc.pubKeyInfo();
        PublicKey pub = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(info.publicKey())));

        SecretKey aes = aesKey();
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(Cipher.ENCRYPT_MODE, pub, OAEP);
        String wrapped = Base64.getEncoder().encodeToString(c.doFinal(aes.getEncoded()));

        SecretKey recovered = svc.unwrapAesKey(wrapped);
        String envelope = svc.seal(recovered, "{\"ok\":true}");
        assertThat(svc.open(new SecretKeySpec(aes.getEncoded(), "AES"), envelope)).isEqualTo("{\"ok\":true}");
    }

    /** 密钥文件必须跨重启复用（否则客户端缓存公钥失效链路天天发生）。 */
    @Test
    void keyFileIsReusedAcrossRestarts() {
        String fp1 = service().pubKeyInfo().fingerprint();
        String fp2 = service().pubKeyInfo().fingerprint();
        assertThat(fp2).isEqualTo(fp1);
        assertThat(Files.exists(tempDir.resolve("server.key"))).isTrue();
    }

    @Test
    void customKeyFileLocationIsUsed() {
        Path custom = tempDir.resolve("keys/custom.key");
        new CryptoService(tempDir.toString(), custom.toString(), mapper);
        assertThat(Files.exists(custom)).isTrue();
    }

    /** 指纹 = sha256(SPKI)，客户端据此核对公钥（TOFU 校对）。 */
    @Test
    void pubKeyInfoFingerprintMatchesSpkiSha256() throws Exception {
        CryptoService.PubKeyInfo info = service().pubKeyInfo();
        assertThat(info.version()).isEqualTo(1);
        assertThat(info.alg()).isEqualTo("RSA-OAEP-SHA256");
        assertThat(info.fingerprint()).matches("[0-9a-f]{64}");

        byte[] spki = Base64.getDecoder().decode(info.publicKey());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(spki);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        assertThat(info.fingerprint()).isEqualTo(hex.toString());
    }
}
