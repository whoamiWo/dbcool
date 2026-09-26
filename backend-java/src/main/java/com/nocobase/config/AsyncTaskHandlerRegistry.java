package com.nocobase.config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PHASE 55 Stage 2 — Handler 注册表。
 *
 * <p>启动时收集所有 {@link AsyncTaskHandler} bean,按 task.type 建立索引。
 * 未注册的 type 被拒收(不静默丢弃)。
 */
@Component
public class AsyncTaskHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskHandlerRegistry.class);

    private final Map<String, AsyncTaskHandler> handlers = new ConcurrentHashMap<>();

    public AsyncTaskHandlerRegistry(List<AsyncTaskHandler> handlerBeans) {
        for (AsyncTaskHandler h : handlerBeans) {
            log.info("[mq] 注册异步任务 handler: {}", h.getClass().getSimpleName());
            // handler 自报支持的 type
            for (String type : h.supportedTypes()) {
                if (handlers.putIfAbsent(type, h) != null) {
                    throw new IllegalStateException(
                            "重复注册异步任务 handler: type=" + type
                                    + " handler=" + h.getClass().getName());
                }
            }
        }
    }

    public AsyncTaskHandler find(String type) {
        return handlers.get(type);
    }

    public boolean contains(String type) {
        return handlers.containsKey(type);
    }
}
