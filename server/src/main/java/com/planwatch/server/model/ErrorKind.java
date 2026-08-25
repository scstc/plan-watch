package com.planwatch.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 错误三分类（序列化值与前端 TS 类型一致）：
 * AUTH=Key 无效/过期，BUSINESS=接口业务失败（含解析失败），NETWORK=瞬时网络失败（保留旧数据）。
 */
public enum ErrorKind {
    @JsonProperty("auth") AUTH,
    @JsonProperty("business") BUSINESS,
    @JsonProperty("network") NETWORK;
}


