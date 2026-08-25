package com.planwatch.server.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS：桌面端 webview（tauri://localhost 等）跨域 fetch 需要放行加密 header。
 * 取代原先 ApiController 上的 @CrossOrigin(origins="*")（避免两处配置合并的不确定性）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("Content-Type", CryptoFilter.KEY_HEADER)
                .maxAge(3600);
    }
}
