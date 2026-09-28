package com.nocobase.ai;

import java.util.List;

/** 语义搜索响应 DTO. */
public record SemanticSearchResponse(
    List<SemanticResult> results,
    int total
) {}
