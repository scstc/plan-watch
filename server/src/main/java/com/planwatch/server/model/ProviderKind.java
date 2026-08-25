package com.planwatch.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 供应商（序列化值与前端 TS 类型一致） */
public enum ProviderKind {
    @JsonProperty("minimax") MINIMAX,
    @JsonProperty("zhipu") ZHIPU;

    public String label() {
        return this == MINIMAX ? "MiniMax" : "智谱 GLM";
    }
}


