package com.planwatch.server.crypto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 互通金标准向量测试：webcrypto-vector.json 由 Node 内置 webcrypto 生成（gen-vector.mjs），
 * 完整模拟前端 WebCrypto 客户端的加密行为。Java 侧能用向量私钥还原出明文，
 * 即证明 CryptoService 与 WebCrypto（RSA-OAEP-SHA256 + AES-256-GCM）逐字节互通。
 */
class CryptoInteropVectorTest {

    @TempDir
    Path tempDir;

    @Test
    void webCryptoClientPayloadDecryptsInJava() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode vector = mapper.readTree(Files.readString(Path.of(
                getClass().getResource("/crypto/webcrypto-vector.json").toURI())));

        // 向量私钥作为服务端密钥文件（模拟服务端拿到该密钥对的场景）
        Path keyFile = tempDir.resolve("interop.key");
        Files.writeString(keyFile, vector.get("privateKeyPem").asText());
        CryptoService svc = new CryptoService(tempDir.toString(), keyFile.toString(), mapper);

        SecretKey aes = svc.unwrapAesKey(vector.get("wrappedKey").asText());
        String envelopeJson = mapper.writeValueAsString(vector.get("envelope"));

        assertThat(svc.open(aes, envelopeJson)).isEqualTo(vector.get("plaintext").asText());
    }
}
