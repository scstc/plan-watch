package com.planwatch.server.service;

import tools.jackson.databind.ObjectMapper;
import com.planwatch.server.model.AppConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 明文 JSON 配置存储，语义与桌面端 src-tauri/src/config.rs 一致：
 * - 损坏文件备份为 config.json.corrupt（诊断用），.bak 专用于上一份好配置
 * - 保存时：旧文件复制为 .bak → 写 tmp → 原子替换
 */
@Component
public class ConfigStore {

    private final Path configFile;
    private final ObjectMapper mapper;
    private volatile AppConfig config;

    public ConfigStore(@Value("${planwatch.data-dir}") String dataDir, ObjectMapper mapper) {
        this.configFile = Path.of(dataDir).resolve("config.json");
        this.mapper = mapper;
        this.config = load();
    }

    public synchronized AppConfig get() {
        return config;
    }

    /** 保存（先规范化）并落盘，返回规范化后的配置。 */
    public synchronized AppConfig save(AppConfig next) {
        AppConfig sanitized = next.sanitized();
        try {
            Files.createDirectories(configFile.getParent());
            Path bak = configFile.resolveSibling("config.json.bak");
            if (Files.exists(configFile)) {
                Files.copy(configFile, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = configFile.resolveSibling("config.json.tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sanitized),
                    StandardCharsets.UTF_8);
            Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("保存配置失败: " + e.getMessage(), e);
        }
        this.config = sanitized;
        return sanitized;
    }

    private AppConfig load() {
        try {
            if (!Files.exists(configFile)) {
                return AppConfig.defaults();
            }
            return mapper.readValue(Files.readString(configFile, StandardCharsets.UTF_8), AppConfig.class);
        } catch (IOException e) {
            System.err.println("config.json 解析失败（" + e.getMessage() + "），使用默认配置并备份原文件");
            try {
                Files.writeString(configFile.resolveSibling("config.json.corrupt"),
                        Files.readString(configFile, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
            return AppConfig.defaults();
        }
    }
}

