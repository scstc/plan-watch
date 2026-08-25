package com.planwatch.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 站点（序列化值与前端 TS 类型一致） */
public enum Region {
    @JsonProperty("cn") CN,
    @JsonProperty("global") GLOBAL;

    public String label() {
        return this == CN ? "国内" : "国际";
    }
}


