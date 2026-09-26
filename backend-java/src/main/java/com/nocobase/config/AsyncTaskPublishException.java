package com.nocobase.config;

/**
 * PHASE 55 Stage 2 — 任务发布异常(broker 不可达 / 路由失败)。
 *
 * <p>不静默吞异常:调用方需决定回滚或告警。
 */
public class AsyncTaskPublishException extends RuntimeException {
    public AsyncTaskPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
