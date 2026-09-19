package com.nocobase.ai;

import java.util.UUID;

/**
 * Agent 工具执行上下文（Phase 48 审计修复新增）。
 *
 * @param tenantId 租户 ID，用于工具层做租户隔离
 * @param userId   当前操作用户 ID
 * @param channelId 当前频道 ID
 */
public record AgentToolContext(String tenantId, UUID userId, UUID channelId) {}
