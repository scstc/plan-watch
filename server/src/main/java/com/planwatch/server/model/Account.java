package com.planwatch.server.model;

/** 单个监控账号（与前端 TS Account 字段一一对应，Jackson camelCase） */
public record Account(
        String id,
        String name,
        ProviderKind provider,
        Region region,
        String apiKey,
        boolean enabled) {

    public Account withId(String newId) {
        return new Account(newId, name, provider, region, apiKey, enabled);
    }
}
