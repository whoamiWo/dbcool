package com.nocobase.ai;

import java.util.Map;

/**
 * Agent 工具接口（SPI）。
 */
public interface AgentTool {
    String name();
    String description();
    Map<String, Object> execute(Map<String, Object> params);
}