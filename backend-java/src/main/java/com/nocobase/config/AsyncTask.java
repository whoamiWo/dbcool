package com.nocobase.config;

import java.util.Map;
import java.util.UUID;

/**
 * PHASE 55 Stage 2 — 统一异步任务契约。
 *
 * <p>所有需要 MQ 补偿的异步任务都封装为 AsyncTask,由
 * {@link AsyncTaskPublisher} 发送到业务 exchange,
 * 由 {@link AsyncTaskHandler} 消费;失败进重试队列,超限进死信。
 *
 * <p>字段全部可序列化为 JSON(RabbitMQ 用 Jackson2JsonMessageConverter)。
 */
public final class AsyncTask {

    private final String type;
    private final String id;
    private final String tenantId;
    private final Map<String, Object> payload;
    private final UUID createdBy;
    private final String traceId;

    public AsyncTask(String type, String id, String tenantId,
                     Map<String, Object> payload, UUID createdBy, String traceId) {
        this.type = type;
        this.id = id;
        this.tenantId = tenantId;
        this.payload = payload;
        this.createdBy = createdBy;
        this.traceId = traceId;
    }

    public String getType() { return type; }
    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public Map<String, Object> getPayload() { return payload; }
    public UUID getCreatedBy() { return createdBy; }
    public String getTraceId() { return traceId; }
}