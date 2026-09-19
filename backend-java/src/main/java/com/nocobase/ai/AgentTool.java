package com.nocobase.ai;

import java.util.Map;
import java.util.UUID;

/**
 * Agent 工具接口（SPI）。
 *
 * <p>Phase 48 审计修复：扩展 ctx 参数以传递租户/用户/频道上下文，
 * 使工具实现能够做租户隔离与权限校验。
 */
public interface AgentTool {
    String name();
    String description();
    Map<String, Object> execute(Map<String, Object> params, AgentToolContext ctx);
}