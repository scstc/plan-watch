package com.planwatch.server.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个限额窗口（与前端 TS QuotaTier 字段一一对应）。
 * usedPercent 统一为"已用"语义（两个供应商原始语义在解析层归一）。
 */
public record QuotaTier(
        WindowKind window,
        double usedPercent,
        @JsonInclude(JsonInclude.Include.ALWAYS) String resetsAt,
        @JsonInclude(JsonInclude.Include.ALWAYS) Double used,
        @JsonInclude(JsonInclude.Include.ALWAYS) Double total,
        @JsonInclude(JsonInclude.Include.ALWAYS) Double remaining,
        boolean unlimited) {
}


