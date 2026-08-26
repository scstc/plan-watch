package com.planwatch.server.web;

import com.planwatch.server.auth.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 引导配对端点：客户端首次接入提交 {code, name} → 拿到 {token, pairedAt}。
 * AuthFilter 对本端点豁免（拿到 token 之前没有 token 可用，构成自举）。
 */
@RestController
public class PairController {

    public record PairRequest(String code, String name) {}

    private final AuthService auth;

    public PairController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/api/pair")
    public Map<String, Object> pair(@RequestBody PairRequest req) {
        // 接受带 dash 的形式（如 "1234-5678"），统一去 dash 后比对
        String normalized = req.code() == null ? "" : req.code().replace("-", "").trim();
        AuthService.TokenRecord record = auth.pair(normalized, req.name());
        return Map.of(
                "token", record.token(),
                "pairedAt", record.pairedAt());
    }
}
