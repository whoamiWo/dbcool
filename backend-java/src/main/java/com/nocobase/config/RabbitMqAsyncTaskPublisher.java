package com.nocobase.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PHASE 55 Stage 2/4 — RabbitMQ 实现。
 *
 * <p>失败语义:broker 不可达或消息无法路由时抛出
 * {@link AsyncTaskPublishException},由调用方决定回滚/告警 —— 不静默吞异常。
 *
 * <p>发布前<b>真实落库</b> {@code mq_task_status}(V36,PENDING),
 * 使异步任务可被追踪/补偿(Stage 4 接真:消除"表只建不写"断链)。
 */
@Component
public class RabbitMqAsyncTaskPublisher implements AsyncTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqAsyncTaskPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter messageConverter;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RabbitMqAsyncTaskPublisher(RabbitTemplate rabbitTemplate,
                                       MessageConverter messageConverter,
                                       JdbcTemplate jdbc,
                                       ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageConverter = messageConverter;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(AsyncTask task) {
        try {
            // 1. 先落库任务状态(PENDING),保证任务可追踪
            persistTaskStatus(task);

            // 2. 再发布到 MQ
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
        } catch (AsyncTaskPublishException e) {
            throw e;
        } catch (Exception e) {
            log.error("[mq] 任务状态落库失败 type={} id={} — 拒绝发布: {}",
                    task.getType(), task.getId(), e.getMessage(), e);
            throw new AsyncTaskPublishException(
                    "任务状态落库失败,拒绝发布: " + task.getType() + "/" + task.getId(), e);
        }
    }

    /** upsert mq_task_status(V36),发布前置为 PENDING,重试时重置 attempts。 */
    private void persistTaskStatus(AsyncTask task) throws Exception {
        String payloadJson = task.getPayload() != null
                ? objectMapper.writeValueAsString(task.getPayload())
                : "{}";
        jdbc.update(
                "INSERT INTO mq_task_status (task_type, task_id, tenant_id, status, attempts, payload) " +
                "VALUES (?, ?, ?, 'PENDING', 0, CAST(? AS jsonb)) " +
                "ON CONFLICT (task_id, task_type) DO UPDATE " +
                "SET status = 'PENDING', attempts = 0, updated_at = NOW()",
                task.getType(),
                task.getId(),
                task.getTenantId(),
                payloadJson
        );
    }
}