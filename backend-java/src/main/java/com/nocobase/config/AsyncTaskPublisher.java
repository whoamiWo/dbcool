package com.nocobase.config;

/**
 * PHASE 55 Stage 2 — 异步任务发布契约。
 *
 * <p>实现类将任务发送到业务 exchange {@code nocobase.task},
 * 绑定 routing-key = 任务 type。失败由队列死信机制自动退避重试,
 * 超限进入死信队列,由补偿作业或告警消费。
 */
public interface AsyncTaskPublisher {
    void publish(AsyncTask task);
}
