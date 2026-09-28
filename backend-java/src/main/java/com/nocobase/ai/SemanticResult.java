package com.nocobase.ai;

import java.util.UUID;

/** 语义搜索结果项 DTO. */
public record SemanticResult(
    UUID documentId,
    String title,
    String snippet,
    double similarity
) {}
