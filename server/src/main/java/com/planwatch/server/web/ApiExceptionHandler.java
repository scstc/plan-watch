package com.planwatch.server.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把异常处理拉回 REQUEST dispatch（默认走容器 /error 的 ERROR dispatch，
 * 会绕过 CryptoFilter 的响应加密），使加密请求的错误响应同样是信封密文。
 * 错误体形如 {"error":"PW_...","message":...}，与 CryptoFilter 的明文错误同构。
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

    /** 覆盖 ConfigStore 落盘失败（IllegalStateException）等业务侧校验错误。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ErrorBody> illegal(RuntimeException e) {
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
