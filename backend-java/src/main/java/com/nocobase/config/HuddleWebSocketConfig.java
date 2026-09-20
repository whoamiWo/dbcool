package com.nocobase.config;

import com.nocobase.auth.JwtService;
import com.nocobase.im.HuddleSignalingHandler;
import com.nocobase.realtime.StompHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Huddle 原生 WebSocket 信令配置。
 *
 * <p>信令端点 {@code /ws/huddle}（原生 WS，不走 STOMP，鉴权由
 * {@link com.nocobase.config.SecurityConfig#securityFilterChain} 中的
 * "/ws/im/**" 放行规则 + {@link StompHandshakeInterceptor} 共享 JWT 校验）。
 */
@Configuration
@EnableWebSocket
public class HuddleWebSocketConfig implements WebSocketConfigurer {

    private final JwtService jwtService;

    public HuddleWebSocketConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new HuddleSignalingHandler(), "/ws/huddle")
                .addInterceptors(new StompHandshakeInterceptor(jwtService))
                .setAllowedOriginPatterns("*");
    }
}