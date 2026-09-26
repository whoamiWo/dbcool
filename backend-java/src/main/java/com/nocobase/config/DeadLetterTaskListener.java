package com.nocobase.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

/**
 * PHASE 55 Stage 2 — 死信队列消费监听。
 *
 * <p>消费 {@code nocobase.task.dlq} 上的超限任务:
 * <ul>
 *   <li>记录 ERROR 日志(告警入口)</li>
 *   <li>持久化到 mq_dead_letter_alert 表,供人工补偿</li>
 * </ul>
 */
@Component
public class DeadLetterTaskListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterTaskListener.class);

    private final Jackson2JsonMessageConverter messageConverter;

    public DeadLetterTaskListener(MessageConverter messageConverter) {
        this.messageConverter = (Jackson2JsonMessageConverter) messageConverter;
    }

    @RabbitListener(queues = AmqpConfig.DLQ_QUEUE)
    public void onMessage(Message message, Channel channel) {
        try {
            Object payload = messageConverter.fromMessage(message);
            AsyncTask task = (AsyncTask) payload;
            log.error("[mq-dlq] 任务超限进入死信队列 type={} id={} tenant={}",
                    task != null ? task.getType() : "unknown",
                    task != null ? task.getId() : "unknown",
                    task != null ? task.getTenantId() : "unknown");
            // TODO: 持久化到 mq_dead_letter_alert 表,触发告警
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("[mq-dlq] 死信消息处理异常: {}", e.getMessage(), e);
        }
    }
}