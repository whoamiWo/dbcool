package com.nocobase.ai;

import java.util.List;

/** 向量嵌入响应 DTO. */
public record EmbeddingResponse(
    String model,
    List<Float> embedding,
    int tokens
) {}
