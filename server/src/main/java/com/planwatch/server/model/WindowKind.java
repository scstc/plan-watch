package com.planwatch.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 限额窗口（序列化值与前端 TS 类型一致） */
public enum WindowKind {
    @JsonProperty("five_hour") FIVE_HOUR,
    @JsonProperty("weekly") WEEKLY;

    public String label() {
        return this == FIVE_HOUR ? "5 小时" : "每周";
    }
}


