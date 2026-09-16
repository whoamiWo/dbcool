package com.nocobase.config;

import com.nocobase.realtime.RedisStompBridge;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 订阅 Redis 广播并转投给本实例 session(补齐 simple broker 的单 JVM 限制)。
 *
 * <p>Spring Boot 自动配置已提供 {@code RedisConnectionFactory}
 * (见 application.yml 的 spring.data.redis),此处只需注册监听器容器。
 */
@Configuration
public class RedisStompBridgeConfig {

    @Bean
    public RedisMessageListenerContainer stompBridgeListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisStompBridge bridge
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> {
                    byte[] body = message.getBody();
                    if (body != null) {
                        bridge.onRemoteMessage(new String(body, StandardCharsets.UTF_8));
                    }
                },
                new ChannelTopic(RedisStompBridge.REDIS_CHANNEL));
        return container;
    }
}
