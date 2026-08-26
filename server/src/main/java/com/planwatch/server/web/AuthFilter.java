package com.planwatch.server.web;

import com.planwatch.server.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Bearer token 认证过滤器（替代原 X-PW-Client/X-PW-Sig ECDSA）：
 * - 每个 /api/* 请求（/api/pair 与 CORS preflight 除外）须带 Authorization: Bearer <token>
 * - token 校验交由 AuthService（mtime 热加载，data/tokens.json 删行即吊销）
 * - 缺 header / 无效 token → 401 PW_AUTH_REQUIRED（错误码让客户端识别"需重新配对"）
 */
@Component
@Order(1)
public class AuthFilter extends OncePerRequestFilter {

    /** 引导端点（签发 token）—— 拿 token 之前没有 token 可用，本端点豁免。 */
    static final String PAIR_PATH = "/api/pair";

    private final AuthService auth;
    private final ObjectMapper mapper;

    public AuthFilter(AuthService auth, ObjectMapper mapper) {
        this.auth = auth;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || PAIR_PATH.equals(path)
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (auth.verify(request.getHeader("Authorization")).isEmpty()) {
            writeError(response, 401, "PW_AUTH_REQUIRED",
                    "设备未配对：缺少或非法的 Authorization: Bearer <token>"
                            + "（在客户端 设置→通用设置「设备配对」输入服务端启动日志中的配对码）");
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.reset();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(Map.of("error", code, "message", message)));
    }
}
