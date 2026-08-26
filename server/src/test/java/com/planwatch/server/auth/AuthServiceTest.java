package com.planwatch.server.auth;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AuthService 单测：配对码生成/持久化/格式校验、token 签发/校验/锁定、
 * tokens.json 原子写入与 mtime 热加载。零 Spring 上下文。
 */
class AuthServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── 配对码 ──

    @Test
    void firstStartGeneratesAndPersistsCode(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        assertThat(auth.code()).matches("\\d{8}");
        assertThat(Files.readString(tmp.resolve("pair.code"), StandardCharsets.UTF_8).trim())
                .isEqualTo(auth.code());
    }

    @Test
    void existingCodeFileIsReused(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pair.code"), "12345678", StandardCharsets.UTF_8);
        AuthService auth = new AuthService(tmp.toString(), mapper);
        assertThat(auth.code()).isEqualTo("12345678");
    }

    @Test
    void malformedCodeFileTriggersRegenerate(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pair.code"), "abcdefgh", StandardCharsets.UTF_8); // 非数字
        AuthService auth = new AuthService(tmp.toString(), mapper);
        assertThat(auth.code()).matches("\\d{8}").isNotEqualTo("abcdefgh");
        assertThat(Files.readString(tmp.resolve("pair.code"), StandardCharsets.UTF_8).trim())
                .isEqualTo(auth.code());
    }

    @Test
    void formatCodeInsertsDashAtMidpoint() {
        assertThat(AuthService.formatCode("12345678")).isEqualTo("1234-5678");
        assertThat(AuthService.formatCode(null)).isNull();
        assertThat(AuthService.formatCode("1234")).isEqualTo("1234");
    }

    // ── 配对 ──

    @Test
    void pairWithCorrectCodeIssuesTokenAndPersists(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        AuthService.TokenRecord record = auth.pair(auth.code(), "test-device");
        assertThat(record.token()).isNotBlank();
        assertThat(record.name()).isEqualTo("test-device");
        assertThat(record.pairedAt()).isPositive();

        // tokens.json 落盘 + 包含本次签发
        Path tokensFile = tmp.resolve("tokens.json");
        assertThat(tokensFile).exists();
        AuthService.TokenRecord[] stored = mapper.readValue(
                Files.readString(tokensFile, StandardCharsets.UTF_8),
                AuthService.TokenRecord[].class);
        assertThat(stored).hasSize(1);
        assertThat(stored[0].token()).isEqualTo(record.token());
    }

    @Test
    void pairWithBlankNameDefaultsToUnnamed(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        AuthService.TokenRecord record = auth.pair(auth.code(), "  ");
        assertThat(record.name()).isEqualTo("未命名设备");
    }

    @Test
    void pairWithWrongCodeThrowsAndCountsFailures(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        for (int i = 0; i < 4; i++) {
            int finalI = i;
            assertThatThrownBy(() -> auth.pair("00000000", "x"))
                    .isInstanceOf(AuthService.AuthException.class)
                    .satisfies(e -> assertThat(((AuthService.AuthException) e).code)
                            .isEqualTo("PW_PAIR_BAD"));
            // 仍未锁定
            assertThat(auth.pair(auth.code(), "x").token()).isNotBlank();
        }
    }

    @Test
    void fiveFailuresLockPairingForFiveMinutes(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> auth.pair("00000000", "x"))
                    .isInstanceOf(AuthService.AuthException.class)
                    .satisfies(e -> assertThat(((AuthService.AuthException) e).code)
                            .isEqualTo("PW_PAIR_BAD"));
        }
        // 第 6 次（即便码正确）触发锁定
        assertThatThrownBy(() -> auth.pair(auth.code(), "x"))
                .isInstanceOf(AuthService.AuthException.class)
                .satisfies(e -> assertThat(((AuthService.AuthException) e).code)
                        .isEqualTo("PW_PAIR_LOCKED"));
    }

    @Test
    void nullCodeIsTreatedAsWrongCode(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        assertThatThrownBy(() -> auth.pair(null, "x"))
                .isInstanceOf(AuthService.AuthException.class)
                .satisfies(e -> assertThat(((AuthService.AuthException) e).code)
                        .isEqualTo("PW_PAIR_BAD"));
    }

    // ── 校验 ──

    @Test
    void verifyAcceptsValidBearerToken(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        AuthService.TokenRecord issued = auth.pair(auth.code(), "d");
        Optional<AuthService.TokenRecord> matched = auth.verify("Bearer " + issued.token());
        assertThat(matched).isPresent();
        assertThat(matched.get().token()).isEqualTo(issued.token());
    }

    @Test
    void verifyRejectsCaseInsensitiveBearerScheme(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        AuthService.TokenRecord issued = auth.pair(auth.code(), "d");
        assertThat(auth.verify("bearer " + issued.token())).isPresent();
        assertThat(auth.verify("BEARER " + issued.token())).isPresent();
    }

    @Test
    void verifyRejectsMissingOrMalformedHeader(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        AuthService.TokenRecord issued = auth.pair(auth.code(), "d");
        assertThat(auth.verify(null)).isEmpty();
        assertThat(auth.verify("")).isEmpty();
        assertThat(auth.verify("Bearer ")).isEmpty(); // 空 token
        assertThat(auth.verify("Basic abc")).isEmpty();
        assertThat(auth.verify("Bearer wrong-token")).isEmpty();
        // 留个已签发的确认未被污染
        assertThat(auth.verify("Bearer " + issued.token())).isPresent();
    }

    @Test
    void verifyHotReloadsTokensAfterFileEdited(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        AuthService.TokenRecord issued = auth.pair(auth.code(), "d");
        assertThat(auth.verify("Bearer " + issued.token())).isPresent();

        // 等 mtime 推进 + 覆盖 tokens.json 为空 → token 失效
        Thread.sleep(15);
        Path tokensFile = tmp.resolve("tokens.json");
        Files.writeString(tokensFile, "[]", StandardCharsets.UTF_8);
        assertThat(auth.verify("Bearer " + issued.token())).isEmpty();
    }

    @Test
    void verifySurvivesMissingTokensFile(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        // 没有任何 pair() 调用 → tokens.json 不存在
        assertThat(auth.verify("Bearer anything")).isEmpty();
    }

    @Test
    void verifyContinuesAfterCorruptTokensFile(@TempDir Path tmp) throws Exception {
        AuthService auth = new AuthService(tmp.toString(), mapper);
        AuthService.TokenRecord issued = auth.pair(auth.code(), "d");
        assertThat(auth.verify("Bearer " + issued.token())).isPresent();

        // 损坏文件 → 沿用上次成功加载的缓存，不抛异常
        Files.writeString(tmp.resolve("tokens.json"), "{not json", StandardCharsets.UTF_8);
        assertThat(auth.verify("Bearer " + issued.token())).isPresent();
    }
}
