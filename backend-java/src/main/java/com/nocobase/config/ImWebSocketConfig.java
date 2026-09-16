package com.nocobase.config;

import com.nocobase.auth.JwtService;
import com.nocobase.realtime.StompDestinations;
import com.nocobase.realtime.StompHandshakeInterceptor;
import com.nocobase.realtime.TenantSubscriptionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 统一实时消息总线配置(STOMP over WebSocket / SockJS)。
 *
 * <p>端点使用 {@code /ws/im},与既有原生端点 {@code /ws/alerts} 区分,
 * 避免 {@code WebSocketConfigurer} 与 {@code WebSocketMessageBrokerConfigurer}
 * 在同一路径前缀上冲突。
 *
 * <p>鉴权分工:
 * <ul>
 *   <li>SecurityConfig 放行 {@code /ws/im/**}(WS 握手带不了 Authorization 头)</li>
 *   <li>{@link StompHandshakeInterceptor} 在握手阶段校验 JWT</li>
 *   <li>{@link TenantSubscriptionInterceptor} 在订阅阶段校验租户</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class ImWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtService jwtService;

    public ImWebSocketConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // simple broker 负责本实例 session 投递;跨实例由 RedisStompBridge 补齐
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes(StompDestinations.APP_PREFIX);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(StompDestinations.ENDPOINT)
                .setAllowedOriginPatterns("*")
                .addInterceptors(new StompHandshakeInterceptor(jwtService))
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new TenantSubscriptionInterceptor());
    }
}
