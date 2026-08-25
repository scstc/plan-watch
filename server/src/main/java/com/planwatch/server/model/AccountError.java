package com.planwatch.server.model;

/** 单账号错误（与前端 TS AccountError 字段一一对应） */
public record AccountError(ErrorKind kind, String message) {
}
