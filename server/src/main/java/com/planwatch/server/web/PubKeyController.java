package com.planwatch.server.web;

import com.planwatch.server.crypto.CryptoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 明文引导接口：客户端首次连接（或公钥轮换后）在此获取服务端公钥。
 * 该端点被 CryptoFilter 豁免 —— 公钥本身即公开信息。
 */
@RestController
public class PubKeyController {

    private final CryptoService cryptoService;

    public PubKeyController(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @GetMapping("/api/pubkey")
    public CryptoService.PubKeyInfo publicKey() {
        return cryptoService.pubKeyInfo();
    }
}
