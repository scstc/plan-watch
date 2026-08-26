package com.planwatch.server.web;

import com.planwatch.server.auth.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一异常 → {"error":"PW_...","message":...} 错误体。把异常处理拉回 REQUEST dispatch，
 * 避免容器 /error 的 ERROR dispatch 产生额外跳转（也确保 AuthFilter 拿不到 token 时
 * 401 响应与正常 401 行为一致）。错误码与 AuthService 错误体同构。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorBody(String error, String message) {}

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, "PW_BAD_REQUEST", "请求体不是合法 JSON");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> notFound(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "PW_NOT_FOUND", "接口不存在: " + e.getResourcePath());
    }

    /** 认证类失败（错误配对码 / 锁定）→ 401，code 透传（PW_PAIR_BAD / PW_PAIR_LOCKED）。 */
    @ExceptionHandler(AuthService.AuthException.class)
    public ResponseEntity<ErrorBody> auth(AuthService.AuthException e) {
        return respond(HttpStatus.UNAUTHORIZED, e.code,
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    /** 客户端提交内容校验失败（如新账号缺 API Key）→ 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> badRequest(IllegalArgumentException e) {
        return respond(HttpStatus.BAD_REQUEST, "PW_BAD_CONFIG",
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    /** 覆盖 ConfigStore 落盘失败（IllegalStateException）等服务端内部错误。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorBody> illegal(IllegalStateException e) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "PW_SERVER_ERROR",
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception e) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "PW_SERVER_ERROR",
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    private ResponseEntity<ErrorBody> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(code, message));
    }
}
