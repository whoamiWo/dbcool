package com.nocobase.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PHASE 55 Stage 2 — RabbitMQ 底座。
 *
 * <p>统一交换机/队列/绑定,支持:
 * <ul>
 *   <li>业务 exchange:{@code nocobase.task} — 异步任务分发</li>
 *   <li>死信 exchange:{@code nocobase.task.dlx} — 失败任务回收</li>
 *   <li>重试队列:{@code nocobase.task.retry} — 逐次退避重试</li>
 *   <li>死信队列:{@code nocobase.task.dlq} — 超限任务归档</li>
 * </ul>
 *
 * <p>消息体为 JSON,通过 Jackson2JsonMessageConverter 序列化。
 */
@Configuration
public class AmqpConfig {

    public static final String TASK_EXCHANGE = "nocobase.task";
    public static final String TASK_QUEUE = "nocobase.task.queue";
    public static final String RETRY_QUEUE = "nocobase.task.retry";
    public static final String DLX_EXCHANGE = "nocobase.task.dlx";
    public static final String DLQ_QUEUE = "nocobase.task.dlq";
    public static final String ROUTING_KEY_TASK = "task";
    public static final String ROUTING_KEY_RETRY = "retry";
    public static final String HEADER_ATTEMPTS = "x-attempts";
    public static final String HEADER_MAX_ATTEMPTS = "x-max-attempts";
    public static final int MAX_ATTEMPTS_DEFAULT = 5;

    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange(TASK_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    /** 主业务队列:绑定死信 exchange,失败自动进 retry。 */
    @Bean
    public Queue taskQueue() {
        return QueueBuilder.durable(TASK_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_RETRY)
                .build();
    }

    /** 重试队列:逐次死信回主队列,带 TTL 退避。 */
    @Bean
    public Queue retryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", TASK_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_TASK)
                .withArgument("x-message-ttl", 1000) // 逐次退避 1s 起
                .build();
    }

    /** 死信队列:超限任务归档,由人工/告警消费。 */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding taskBinding() {
        return BindingBuilder.bind(taskQueue())
                .to(taskExchange())
                .with(ROUTING_KEY_TASK);
    }

    @Bean
    public Binding retryBinding() {
        return BindingBuilder.bind(retryQueue())
                .to(deadLetterExchange())
                .with(ROUTING_KEY_RETRY);
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with("*"); // 兜底:任何未匹配的死信都进 dlq
    }

    @Bean
    public MessageConverter messageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setCreateMessageIds(true);
        return converter;
    }
}