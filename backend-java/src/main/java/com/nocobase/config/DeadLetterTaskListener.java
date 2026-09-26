package com.nocobase.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

/**
 * PHASE 55 Stage 2/4 — 死信队列消费监听。
 *
 * <p>消费 {@code nocobase.task.dlq} 上的超限任务:
 * <ul>
 *   <li>记录 ERROR 日志(告警入口)</li>
 *   <li><b>真实持久化</b>到 {@code mq_dead_letter_alert} 表,供人工补偿
 *       (Stage 4 接真:消除此前 "TODO 持久化" 空实现,避免 V36 表只建不写)</li>
 * </ul>
 *
 * <p><b>失败语义</b>:持久化失败抛异常且<b>不 ack</b>,让消息重回队列 —— 禁静默丢弃死信。
 */
@Component
public class DeadLetterTaskListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterTaskListener.class);

    private final Jackson2JsonMessageConverter messageConverter;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DeadLetterTaskListener(MessageConverter messageConverter,
                                   JdbcTemplate jdbc,
                                   ObjectMapper objectMapper) {
        this.messageConverter = (Jackson2JsonMessageConverter) messageConverter;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = AmqpConfig.DLQ_QUEUE)
    public void onMessage(Message message, Channel channel) throws Exception {
        AsyncTask task = null;
        try {
            Object payload = messageConverter.fromMessage(message);
            task = (payload instanceof AsyncTask) ? (AsyncTask) payload : null;
            log.error("[mq-dlq] 任务超限进入死信队列 type={} id={} tenant={}",
                    task != null ? task.getType() : "unknown",
                    task != null ? task.getId() : "unknown",
                    task != null ? task.getTenantId() : "unknown");

            persistAlert(task);

            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (ClassCastException e) {
            // 消息体不是 AsyncTask:无法补偿,记 ERROR 后 ack 丢弃(避免毒丸无限循环)
            log.error("[mq-dlq] 死信消息反序列化失败,非 AsyncTask,丢弃: {}", e.getMessage(), e);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            // 持久化失败不吞:不 ack,让消息重回队列重试
            log.error("[mq-dlq] 死信持久化失败,不 ack 等待重投: {}", e.getMessage(), e);
            throw e;
        }
    }

    /** 写入 mq_dead_letter_alert(V36),供人工补偿与告警查询。 */
    private void persistAlert(AsyncTask task) throws Exception {
        String payloadJson = task != null && task.getPayload() != null
                ? objectMapper.writeValueAsString(task.getPayload())
                : "{}";
        jdbc.update(
                "INSERT INTO mq_dead_letter_alert (task_type, task_id, tenant_id, payload, error_message) " +
                "VALUES (?, ?, ?, CAST(? AS jsonb), ?)",
                task != null ? task.getType() : "unknown",
                task != null ? task.getId() : "unknown",
                task != null ? task.getTenantId() : "unknown",
                payloadJson,
                "任务重试超限进入死信队列"
        );
    }
}