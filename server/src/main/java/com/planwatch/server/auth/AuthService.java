package com.planwatch.server.auth;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 启动配对码 + Bearer token 认证（替代原 RSA 信封 + ECDSA 设备配对）：
 * - data/pair.code：8 位数字配对码（启动日志打印）；首启生成，删文件 + 重启可作废重发
 * - data/tokens.json：已签发 Bearer token 清单（mtime 热加载，无需重启）；吊销 = 删行
 * - 限速：5 次错误码 → 锁定 5 分钟（防爆破）
 *
 * 协议：客户端在 /api/pair 提交配对码 → 拿到 token → 后续请求带 Authorization: Bearer <token>
 */
@Component
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 一条已签发 token。 */
    public record TokenRecord(String token, String name, long pairedAt) {}

    /** 认证类失败（错误码 / 锁定），统一 401，code 走错误体。 */
    public static class AuthException extends RuntimeException {
        public final String code;

        public AuthException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final int MAX_PAIR_FAILURES = 5;
    private static final long LOCK_MILLIS = 5 * 60 * 1000;
    /** 配对码长度：8 位数字（显示 4-4）。 */
    private static final int CODE_LENGTH = 8;
    /** Bearer token 随机字节数 → URL-safe base64 = 43 字符。 */
    private static final int TOKEN_BYTES = 32;

    private final Path codeFile;
    private final Path tokensFile;
    private final ObjectMapper mapper;

    private final String code;
    private volatile List<TokenRecord> tokens = List.of();
    private volatile long tokensMtime = -1;

    private final AtomicInteger pairFailures = new AtomicInteger();
    private volatile long lockedUntil = 0;

    public AuthService(@Value("${planwatch.data-dir}") String dataDir, ObjectMapper mapper) {
        this.codeFile = Path.of(dataDir).resolve("pair.code");
        this.tokensFile = Path.of(dataDir).resolve("tokens.json");
        this.mapper = mapper;
        this.code = loadOrCreateCode();
        log.info("配对码: {}（客户端 设置→通用设置「设备配对」输入此码；删除 {} 重启可作废重发）",
                formatCode(code), codeFile.toAbsolutePath());
        log.info("已签发 token 存储: {}", tokensFile.toAbsolutePath());
    }

    /** 显示形式：8 位数字按 4-4 分段。 */
    public static String formatCode(String raw) {
        if (raw == null || raw.length() != CODE_LENGTH) {
            return raw;
        }
        return raw.substring(0, 4) + "-" + raw.substring(4);
    }

    /** 当前配对码（去 dash 的原始形式）。 */
    public String code() {
        return code;
    }

    // ── 校验 ──

    /**
     * 校验 Authorization header 里的 Bearer token。返回匹配的记录，或 empty。
     * 热加载 tokens.json（mtime 变化时重读），吊销后无需重启即生效。
     */
    public Optional<TokenRecord> verify(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(7).trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        byte[] submitted = token.getBytes(StandardCharsets.UTF_8);
        for (TokenRecord r : loadTokens()) {
            if (MessageDigest.isEqual(r.token().getBytes(StandardCharsets.UTF_8), submitted)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    // ── 配对 ──

    /** 校验配对码并签发新 token。错误码 / 锁定 → AuthException（→ ApiExceptionHandler 401）。 */
    public synchronized TokenRecord pair(String submittedCode, String name) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) {
            long secs = (lockedUntil - now + 999) / 1000;
            throw new AuthException("PW_PAIR_LOCKED",
                    "配对尝试过于频繁，已锁定 " + secs + " 秒（连续 " + MAX_PAIR_FAILURES + " 次错误码）");
        }
        byte[] expected = code.getBytes(StandardCharsets.UTF_8);
        byte[] actual = submittedCode == null ? new byte[0] : submittedCode.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            if (pairFailures.incrementAndGet() >= MAX_PAIR_FAILURES) {
                lockedUntil = now + LOCK_MILLIS;
                pairFailures.set(0);
            }
            throw new AuthException("PW_PAIR_BAD", "配对码错误（见服务端启动日志）");
        }
        pairFailures.set(0);

        String token = generateToken();
        TokenRecord record = new TokenRecord(token,
                name == null || name.isBlank() ? "未命名设备" : name, now);

        List<TokenRecord> next = new ArrayList<>(loadTokens());
        next.add(record);
        persistTokens(next);
        log.info("设备配对成功: {}（当前已签发 {} 个 token）", record.name(), next.size());
        return record;
    }

    // ── 存储 ──

    private List<TokenRecord> loadTokens() {
        try {
            long mtime = Files.getLastModifiedTime(tokensFile).toMillis();
            if (mtime != tokensMtime) {
                List<TokenRecord> loaded = Files.exists(tokensFile)
                        ? List.of(mapper.readValue(Files.readString(tokensFile, StandardCharsets.UTF_8),
                                TokenRecord[].class))
                        : List.of();
                tokens = loaded;
                tokensMtime = mtime;
            }
        } catch (Exception e) {
            // 文件 IO 或 JSON 解析失败：沿用上次成功加载的缓存，不让 tokens.json 损坏阻塞请求
        }
        return tokens;
    }

    private void persistTokens(List<TokenRecord> next) {
        try {
            Files.createDirectories(tokensFile.getParent());
            Path tmp = tokensFile.resolveSibling("tokens.json.tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(next),
                    StandardCharsets.UTF_8);
            Files.move(tmp, tokensFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            tokens = List.copyOf(next);
            tokensMtime = Files.getLastModifiedTime(tokensFile).toMillis();
        } catch (IOException e) {
            throw new IllegalStateException("保存 tokens.json 失败: " + e.getMessage(), e);
        }
    }

    private String loadOrCreateCode() {
        try {
            if (Files.exists(codeFile)) {
                String existing = Files.readString(codeFile, StandardCharsets.UTF_8).trim();
                if (existing.matches("\\d{" + CODE_LENGTH + "}")) {
                    return existing;
                }
                // 文件存在但格式不对（手动编辑坏的） → 重新生成覆盖
                log.warn("{} 内容不是 {} 位数字，重新生成", codeFile, CODE_LENGTH);
            }
            SecureRandom rng = new SecureRandom();
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append((char) ('0' + rng.nextInt(10)));
            }
            Files.createDirectories(codeFile.getParent());
            Files.writeString(codeFile, sb.toString(), StandardCharsets.UTF_8);
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("读写配对码失败: " + e.getMessage(), e);
        }
    }

    private static String generateToken() {
        byte[] raw = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
