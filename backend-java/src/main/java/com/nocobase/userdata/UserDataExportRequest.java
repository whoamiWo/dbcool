package com.nocobase.userdata;

/**
 * 用户数据导出请求 DTO
 */
public record UserDataExportRequest(
    String exportType  // "full" | "collections" | "im" | "ai"
) {}