package com.nocobase.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.nocobase.alert.ws.AlertWebSocketHandler;

/**
 * R11: WebSocket 配置 — 注册 /ws/alerts 端点.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AlertWebSocketHandler alertHandler;

    @Autowired
    public WebSocketConfig(AlertWebSocketHandler alertHandler) {
        this.alertHandler = alertHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(alertHandler, "/ws/alerts").setAllowedOriginPatterns("*");
    }
}
