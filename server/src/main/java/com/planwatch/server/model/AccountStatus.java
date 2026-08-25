package com.planwatch.server.model;

import java.util.List;

/** 单账号最近一次查询的状态快照（与前端 TS AccountStatus 字段一一对应） */
public record AccountStatus(
        String accountId,
        boolean ok,
        AccountError error,
        String planLevel,
        List<QuotaTier> tiers,
        Long queriedAt,
        boolean stale) {

    public static AccountStatus empty(String accountId) {
        return new AccountStatus(accountId, false, null, null, List.of(), null, false);
    }
}
