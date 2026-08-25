package com.planwatch.server.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 服务端配置（与前端 TS AppConfig 字段一一对应） */
public record AppConfig(
        long refreshIntervalSecs,
        int lowQuotaThreshold,
        List<Account> accounts) {

    public static AppConfig defaults() {
        return new AppConfig(300, 80, new ArrayList<>());
    }

    /** 入库前规范化：钳制数值、补空 id、去重 id（保留首个）。 */
    public AppConfig sanitized() {
        long interval = Math.max(60, Math.min(86_400, refreshIntervalSecs));
        int threshold = Math.max(10, Math.min(99, lowQuotaThreshold));
        List<Account> deduped = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Account a : accounts == null ? List.<Account>of() : accounts) {
            String id = (a.id() == null || a.id().isBlank()) ? UUID.randomUUID().toString() : a.id();
            if (seen.add(id)) {
                deduped.add(a.id().equals(id) ? a : a.withId(id));
            }
        }
        return new AppConfig(interval, threshold, deduped);
    }
}
