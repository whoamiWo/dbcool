package com.nocobase.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis pub/sub 跨实例广播桥接。
 *
 * <p>Spring 内置 simple broker 只在单 JVM 内投递 session ——
 * 多副本部署时,连在实例 B 的客户端收不到实例 A 产生的消息。
 * 这里把要广播的消息同时 publish 到 Redis,由其它实例订阅后
 * 用各自本地的 {@code SimpMessagingTemplate} 转投。
 *
 * <p><strong>为何不用 StompBrokerRelay</strong>:Redis 不是标准 STOMP broker,
 * 且项目约束"不引入新中间件"(不引入 RabbitMQ),故采用 simple broker + 自研桥接。
 *
 * <p>用 {@code instanceId} 丢弃自己 publish 出去的回环消息,避免重复投递。
 */
@Component
public class RedisStompBridge {

    /** 跨实例广播使用的 Redis channel。 */
    public static final String REDIS_CHANNEL = "nocobase:stomp:broadcast";

    private static final Logger log = LoggerFactory.getLogger(RedisStompBridge.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 本实例标识,用于丢弃自己发出的回环消息。 */
    private final String instanceId = UUID.randomUUID().toString();

    public RedisStompBridge(
            @Lazy SimpMessagingTemplate messagingTemplate,
            StringRedisTemplate redis,
            ObjectMapper objectMapper
    ) {
        this.messagingTemplate = messagingTemplate;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 广播:先投本实例 session,再 publish 到 Redis 供其它实例转投。 */
    public void broadcast(String destination, Object payload) {
        if (destination == null || payload == null) return;
        messagingTemplate.convertAndSend(destination, payload);
        try {
            String raw = objectMapper.writeValueAsString(
                    new Envelope(instanceId, destination, payload));
            redis.convertAndSend(REDIS_CHANNEL, raw);
        } catch (Exception e) {
            // 跨实例失败不应影响本实例投递
            log.warn("[ws] Redis 广播失败(destination={}): {}", destination, e.getMessage());
        }
    }

    /**
     * 处理来自其它实例的广播:转投给本实例的 session。
     *
     * @return true=已转投;false=解析失败、字段缺失或是自己发出的回环消息
     */
    public boolean onRemoteMessage(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            Envelope env = objectMapper.readValue(raw, Envelope.class);
            if (env == null || env.senderId() == null) return false;
            if (instanceId.equals(env.senderId())) return false; // 丢弃回环
            if (env.destination() == null || env.payload() == null) return false;
            messagingTemplate.convertAndSend(env.destination(), env.payload());
            return true;
        } catch (Exception e) {
            log.warn("[ws] Redis 消息处理失败: {}", e.getMessage());
            return false;
        }
    }

    /** 跨实例传输信封。 */
    public record Envelope(String senderId, String destination, Object payload) {}
}
