package com.nocobase.config;

/**
 * PHASE 55 Stage 2 — 异步任务消费契约。
 *
 * <p>实现类按 {@link AsyncTask#getType()} 注册到 {@link AsyncTaskHandlerRegistry},
 * 由 {@link AsyncTaskListener} 派发。
 *
 * <p>失败语义:<b>抛异常即进重试</b>(不 catch);重试超限进死信队列。
 */
public interface AsyncTaskHandler {
    void handle(AsyncTask task);
    default int maxRetries() { return AmqpConfig.MAX_ATTEMPTS_DEFAULT; }
    /** 本 handler 支持的任务类型列表(用于注册表索引)。 */
    java.util.List<String> supportedTypes();
}
