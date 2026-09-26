package com.nocobase.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

/**
 * PHASE 55 Stage 2 — 任务消费监听。
 *
 * <p>消费 {@code nocobase.task.queue} 上的 AsyncTask:
 * <ul>
 *   <li>按 type 查注册表找 handler,未注册 → 拒收 + ERROR 告警(不静默丢弃)</li>
 *   <li>handler.handle 抛异常 → 不 catch,由 RabbitMQ 死信机制进重试队列</li>
 *   <li>重试超限进死信队列,由补偿作业或告警消费</li>
 * </ul>
 */
@Component
public class AsyncTaskListener {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskListener.class);

    private final AsyncTaskHandlerRegistry registry;
    private final Jackson2JsonMessageConverter messageConverter;

    public AsyncTaskListener(AsyncTaskHandlerRegistry registry,
                              MessageConverter messageConverter) {
        this.registry = registry;
        this.messageConverter = (Jackson2JsonMessageConverter) messageConverter;
    }

    @RabbitListener(queues = AmqpConfig.TASK_QUEUE)
    public void onMessage(Message message) {
        Object payload = messageConverter.fromMessage(message);
        if (!(payload instanceof AsyncTask)) {
            log.error("[async-task] 消息反序列化失败，类型不符 — 拒收");
            return; // 不抛异常:避免死循环
        }
        AsyncTask task = (AsyncTask) payload;
        AsyncTaskHandler handler = registry.find(task.getType());
        if (handler == null) {
            log.error("[mq] 未注册的异步任务 type={},拒绝放行 — 请检查 handler 注册",
                    task.getType());
            return; // 拒收:不静默丢弃,但也不抛异常(避免无限重试)
        }
        log.info("[mq] 消费任务 type={} id={} tenant={}",
                task.getType(), task.getId(), task.getTenantId());
        handler.handle(task);
        log.info("[mq] 任务完成 type={} id={}", task.getType(), task.getId());
    }
}