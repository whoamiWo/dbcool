package com.nocobase.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * PHASE 55 Stage 2 — RabbitMQ 实现。
 *
 * <p>失败语义:broker 不可达或消息无法路由时抛出
 * {@link AsyncTaskPublishException},由调用方决定回滚/告警 —— 不静默吞异常。
 */
@Component
public class RabbitMqAsyncTaskPublisher implements AsyncTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqAsyncTaskPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter messageConverter;

    public RabbitMqAsyncTaskPublisher(RabbitTemplate rabbitTemplate,
                                       MessageConverter messageConverter) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageConverter = messageConverter;
    }

    @Override
    public void publish(AsyncTask task) {
        try {
            Message msg = messageConverter.toMessage(task, new MessageProperties());
            msg.getMessageProperties().setHeader(AmqpConfig.HEADER_ATTEMPTS, 1);
            msg.getMessageProperties().setHeader(AmqpConfig.HEADER_MAX_ATTEMPTS,
                    AmqpConfig.MAX_ATTEMPTS_DEFAULT);
            rabbitTemplate.send(AmqpConfig.TASK_EXCHANGE, AmqpConfig.ROUTING_KEY_TASK, msg);
            log.debug("[mq] 发布任务 type={} id={} tenant={}",
                    task.getType(), task.getId(), task.getTenantId());
        } catch (AmqpException e) {
            log.error("[mq] 发布任务失败 type={} id={} tenant={} — broker 不可达,拒绝放行",
                    task.getType(), task.getId(), task.getTenantId(), e);
            throw new AsyncTaskPublishException(
                    "RabbitMQ 不可达,任务发布失败: " + task.getType() + "/" + task.getId(), e);
        }
    }
}