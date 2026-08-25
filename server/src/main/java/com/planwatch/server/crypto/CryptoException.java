package com.planwatch.server.crypto;

/**
 * 加解密协议错误（密钥解包失败、信封损坏等），由 CryptoFilter 转为对应 4xx 错误码。
 */
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
