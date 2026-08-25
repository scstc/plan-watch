package com.planwatch.server.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.planwatch.server.crypto.CryptoService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CryptoFilter 集成测试：测试自身用 JCA 独立实现「客户端」加密行为
 * （与前端 WebCrypto 同参数，互通性另由 CryptoInteropVectorTest 保证）。
 * 类内共享同一个 Spring 上下文与 config.json，测试方法间有落盘状态，
 * 断言只针对各自请求的往返，不依赖执行顺序。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiCryptoFilterTest {

    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    @TempDir
    static Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("planwatch.data-dir", () -> tempDir.toString());
        registry.add("planwatch.crypto.required", () -> "true");
    }

    // ── 客户端侧加密助手（独立于 CryptoService 实现） ──

    private PublicKey serverPubKey() throws Exception {
        String body = mockMvc.perform(get("/api/pubkey")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String spki = mapper.readTree(body).get("publicKey").asText();
        return KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(spki)));
    }

    private static SecretKey newAes() throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        return gen.generateKey();
    }

    private String wrapKey(PublicKey pub, SecretKey aes) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(Cipher.ENCRYPT_MODE, pub, OAEP);
        return Base64.getEncoder().encodeToString(c.doFinal(aes.getEncoded()));
    }

    private String sealBody(SecretKey aes, String json) throws Exception {
        byte[] iv = new byte[12];
        new Random().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
        return mapper.writeValueAsString(java.util.Map.of(
                "iv", Base64.getEncoder().encodeToString(iv),
                "data", Base64.getEncoder().encodeToString(ct)));
    }

    private String openBody(SecretKey aes, String envelopeJson) throws Exception {
        JsonNode node = mapper.readTree(envelopeJson);
        byte[] iv = Base64.getDecoder().decode(node.get("iv").asText());
        byte[] ct = Base64.getDecoder().decode(node.get("data").asText());
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, iv));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }

    private String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ── 用例 ──

    @Test
    @org.junit.jupiter.api.Order(1)
    void pubkeyEndpointStaysPlaintext() throws Exception {
        String body = body(mockMvc.perform(get("/api/pubkey")).andExpect(status().isOk()));
        JsonNode node = mapper.readTree(body);
        assertThat(node.get("publicKey").asText()).isNotBlank();
        assertThat(node.get("fingerprint").asText()).matches("[0-9a-f]{64}");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void plaintextApiIsRejectedWhenRequired() throws Exception {
        String body = body(mockMvc.perform(get("/api/config")).andExpect(status().isBadRequest()));
        assertThat(mapper.readTree(body).get("error").asText()).isEqualTo("PW_CRYPTO_REQUIRED");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void encryptedGetReturnsEnvelopeThatDecrypts() throws Exception {
        SecretKey aes = newAes();
        String wrapped = wrapKey(serverPubKey(), aes);
        String raw = body(mockMvc.perform(get("/api/config").header("X-PW-Key", wrapped))
                .andExpect(status().isOk()));
        // 响应必须是信封（不含明文字段），解开后是 AppConfig
        assertThat(raw).contains("\"iv\"").contains("\"data\"");
        JsonNode config = mapper.readTree(openBody(aes, raw));
        assertThat(config.has("accounts")).isTrue();
        assertThat(config.has("refreshIntervalSecs")).isTrue();
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void encryptedPutPersistsAndRoundTrips() throws Exception {
        SecretKey aes = newAes();
        String wrapped = wrapKey(serverPubKey(), aes);
        String configJson = """
                {"refreshIntervalSecs":120,"lowQuotaThreshold":15,"accounts":[
                  {"id":"t1","name":"测试","provider":"minimax","region":"cn",
                   "apiKey":"sk-filter-test","enabled":true}]}""";
        String raw = body(mockMvc.perform(put("/api/config").header("X-PW-Key", wrapped)
                        .contentType("application/json").content(sealBody(aes, configJson)))
                .andExpect(status().isOk()));
        JsonNode saved = mapper.readTree(openBody(aes, raw));
        assertThat(saved.get("accounts").size()).isEqualTo(1);
        assertThat(saved.get("accounts").get(0).get("apiKey").asText()).isEqualTo("sk-filter-test");
        // 落盘的 config.json 也是解密后的明文（服务端自身存储与传输加密解耦）
        assertThat(Files.readString(tempDir.resolve("config.json"))).contains("sk-filter-test");
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void refreshWithoutBodyStillEncryptsResponse() throws Exception {
        SecretKey aes = newAes();
        String wrapped = wrapKey(serverPubKey(), aes);
        String raw = body(mockMvc.perform(post("/api/refresh").header("X-PW-Key", wrapped))
                .andExpect(status().isOk()));
        JsonNode statuses = mapper.readTree(openBody(aes, raw));
        assertThat(statuses.isArray()).isTrue();
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void garbageKeyIsRejected() throws Exception {
        byte[] garbage = new byte[256];
        new Random(7).nextBytes(garbage);
        String body = body(mockMvc.perform(get("/api/config")
                        .header("X-PW-Key", Base64.getEncoder().encodeToString(garbage)))
                .andExpect(status().isBadRequest()));
        assertThat(mapper.readTree(body).get("error").asText()).isEqualTo("PW_KEY_UNWRAP_FAILED");
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    void garbageEnvelopeIsRejected() throws Exception {
        SecretKey aes = newAes();
        String wrapped = wrapKey(serverPubKey(), aes);
        String body = body(mockMvc.perform(put("/api/config").header("X-PW-Key", wrapped)
                        .contentType("application/json").content("garbage-not-json"))
                .andExpect(status().isBadRequest()));
        assertThat(mapper.readTree(body).get("error").asText()).isEqualTo("PW_CRYPTO_BAD");
    }

    /** 加密请求触发的业务异常，错误响应也必须是信封（ApiExceptionHandler 拉回 REQUEST dispatch）。 */
    @Test
    @org.junit.jupiter.api.Order(8)
    void errorMessageIsEncryptedWhenKeyIsValid() throws Exception {
        SecretKey aes = newAes();
        String wrapped = wrapKey(serverPubKey(), aes);
        String raw = body(mockMvc.perform(get("/api/nonexistent").header("X-PW-Key", wrapped))
                .andExpect(status().isNotFound()));
        JsonNode err = mapper.readTree(openBody(aes, raw));
        assertThat(err.get("error").asText()).isEqualTo("PW_NOT_FOUND");
    }

    /** CORS 预检必须放行加密 header，否则 webview 跨域 fetch 根本发不出。 */
    @Test
    @org.junit.jupiter.api.Order(9)
    void corsPreflightAllowsKeyHeader() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/config")
                        .header("Origin", "tauri://localhost")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-pw-key"))
                .andExpect(status().isOk())
                .andReturn();
        String allow = result.getResponse().getHeader("Access-Control-Allow-Headers");
        assertThat(allow).containsIgnoringCase("X-PW-Key");
    }
}
