package com.planwatch.server.web;

import tools.jackson.databind.ObjectMapper;
import com.planwatch.server.crypto.CryptoException;
import com.planwatch.server.crypto.CryptoService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.util.Map;

/**
 * 接口加解密过滤器（协议 v1），对所有 /api/*（/api/pubkey 除外）生效：
 * - 请求：X-PW-Key header = RSA-OAEP 包裹的一次性 AES-256 密钥；body 为 {"iv","data"} 信封，
 *   解开后用明文替换请求体，控制器无感知
 * - 响应：用同一 AES 密钥把 body 加密为信封（配合 ApiExceptionHandler，异常响应也被加密）
 * - planwatch.crypto.required=true（默认）时拒绝未加密请求，防降级嗅探
 */
@Component
@Order(1)
public class CryptoFilter extends OncePerRequestFilter {

    public static final String KEY_HEADER = "X-PW-Key";
    private static final String PUBKEY_PATH = "/api/pubkey";
    /** 请求体上限（config.json 很小，2MB 足够且防滥用） */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final boolean required;

    public CryptoFilter(CryptoService crypto, ObjectMapper mapper,
                        @Value("${planwatch.crypto.required:true}") boolean required) {
        this.crypto = crypto;
        this.mapper = mapper;
        this.required = required;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || PUBKEY_PATH.equals(path)
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String wrapped = request.getHeader(KEY_HEADER);
        if (wrapped == null) {
            if (required) {
                writeError(response, 400, "PW_CRYPTO_REQUIRED",
                        "接口已启用加密，请求需携带 " + KEY_HEADER + "（协议见 GET /api/pubkey）");
                return;
            }
            // 兼容模式：明文放行（curl 直调/旧客户端），响应同样保持明文
            chain.doFilter(request, response);
            return;
        }

        SecretKey aes;
        try {
            aes = crypto.unwrapAesKey(wrapped);
        } catch (CryptoException e) {
            writeError(response, 400, "PW_KEY_UNWRAP_FAILED",
                    "AES 密钥解包失败，请重新获取服务端公钥（GET /api/pubkey）");
            return;
        }

        HttpServletRequest effective = request;
        byte[] raw = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
            writeError(response, 413, "PW_CRYPTO_BAD", "请求体超过上限 " + MAX_BODY_BYTES + " 字节");
            return;
        }
        if (raw.length > 0) {
            String plaintext;
            try {
                plaintext = crypto.open(aes, new String(raw, StandardCharsets.UTF_8));
            } catch (CryptoException e) {
                writeError(response, 400, "PW_CRYPTO_BAD", "请求信封解密失败（密钥或数据损坏）");
                return;
            }
            effective = new PlaintextRequest(request, plaintext.getBytes(StandardCharsets.UTF_8));
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(effective, wrappedResponse);
        } finally {
            byte[] body = wrappedResponse.getContentAsByteArray();
            if (body.length > 0 && !wrappedResponse.isCommitted()) {
                String envelope = crypto.seal(aes, new String(body, StandardCharsets.UTF_8));
                wrappedResponse.resetBuffer();
                wrappedResponse.setContentType("application/json");
                wrappedResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
                wrappedResponse.getWriter().write(envelope);
            }
            wrappedResponse.copyBodyToResponse();
        }
    }

    /** 写明文错误（此时拿不到可信 AES 密钥，无法加密；错误体不含敏感字段）。 */
    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.reset();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(Map.of("error", code, "message", message)));
    }

    /** 用解密后的明文替换原请求体，让 Jackson/@RequestBody 无感知绑定。 */
    private static final class PlaintextRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        PlaintextRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new BodyInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class BodyInputStream extends ServletInputStream {

        private final ByteArrayInputStream buf;

        BodyInputStream(byte[] body) {
            this.buf = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return buf.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            // 同步读取，无需异步回调
        }

        @Override
        public int read() {
            return buf.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return buf.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            return buf.available();
        }

        @Override
        public void close() throws IOException {
            buf.close();
        }
    }
}
