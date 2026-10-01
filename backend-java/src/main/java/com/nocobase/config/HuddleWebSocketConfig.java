package com.nocobase.config;

import com.nocobase.auth.JwtService;
import com.nocobase.im.HuddleSignalingHandler;
import com.nocobase.realtime.StompHandshakeInterceptor;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Huddle 原生 WebSocket 信令配置。
 *
 * <p>信令端点 {@code /ws/huddle}（原生 WS，不走 STOMP，鉴权由
 * {@link com.nocobase.config.SecurityConfig#securityFilterChain} 中的
 * "/ws/im/**" 放行规则 + {@link StompHandshakeInterceptor} 共享 JWT 校验）。
 *
 * <p>多副本部署时通过 Redis pub/sub（频道 {@link HuddleSignalingHandler#REDIS_CHANNEL}）
 * 跨实例转发信令，各副本收到远程消息后发给本机房间成员。
 */
@Configuration
@EnableWebSocket
public class HuddleWebSocketConfig implements WebSocketConfigurer {

    private final JwtService jwtService;
    private final HuddleSignalingHandler huddleSignalingHandler;

    public HuddleWebSocketConfig(JwtService jwtService, HuddleSignalingHandler huddleSignalingHandler) {
        this.jwtService = jwtService;
        this.huddleSignalingHandler = huddleSignalingHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(huddleSignalingHandler, "/ws/huddle")
                .addInterceptors(new StompHandshakeInterceptor(jwtService))
                .setAllowedOriginPatterns("*");
    }

    /**
     * 订阅 Redis Huddle 信令频道，将远程副本的消息交给
     * {@link HuddleSignalingHandler#onRemoteMessage(String)} 分发给本机房间成员。
     * 与 {@code RedisStompBridgeConfig} 同样的容器方式，不另起 Redis 连接配置。
     */
    @Bean
    public RedisMessageListenerContainer huddleBridgeListenerContainer(
            RedisConnectionFactory connectionFactory,
            HuddleSignalingHandler huddleSignalingHandler
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> {
                    byte[] body = message.getBody();
                    if (body != null) {
                        huddleSignalingHandler.onRemoteMessage(new String(body, StandardCharsets.UTF_8));
                    }
                },
                new ChannelTopic(HuddleSignalingHandler.REDIS_CHANNEL));
        return container;
    }
}
