package com.nocobase.alert.ws;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * R11: 告警 WebSocket Handler — 处理 /ws/alerts 端点.
 *
 * 协议:
 * - 连接 URL 支持 ?user_id=xxx(用于按订阅路由)
 * - 连接后服务端发: {"type":"hello","user_id":"...","msg":"connected"}
 * - 每次告警: {"type":"alert", ...AlertEvent.toMap()...}
 * - 客户端 ping: {"type":"ping"} → 服务端回 {"type":"pong"}
 */
@Component
public class AlertWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = Logger.getLogger(AlertWebSocketHandler.class.getName());

    private final AlertBroadcaster broadcaster;
    private final ObjectMapper mapper = new ObjectMapper();

    public AlertWebSocketHandler(AlertBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * 在握手时从 URL query string 解析 user_id,存入 session attributes.
     * Spring 的 afterConnectionEstablished 已经在 accept() 之后才触发,
     * 所以这里可以直接读 session.getUri().
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = extractUserId(session.getUri());
        if (userId != null && !userId.isBlank()) {
            session.getAttributes().put(AlertBroadcaster.ATTR_USER_ID, userId);
        }
        broadcaster.addSession(session);

        Map<String, Object> hello = new HashMap<>();
        hello.put("type", "hello");
        hello.put("user_id", userId);
        hello.put("msg", "connected");
        session.sendMessage(new TextMessage(mapper.writeValueAsString(hello)));
    }

    /** 从 URI 的 query string 提取 user_id 参数. */
    static String extractUserId(URI uri) {
        if (uri == null || uri.getQuery() == null) return null;
        for (String pair : uri.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq);
            if ("user_id".equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.removeSession(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            Map<?, ?> msg = mapper.readValue(message.getPayload(), Map.class);
            if ("ping".equals(msg.get("type"))) {
                Map<String, String> pong = Map.of("type", "pong");
                session.sendMessage(new TextMessage(mapper.writeValueAsString(pong)));
            }
        } catch (Exception e) {
            log.log(Level.FINE, "解析客户端消息失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.log(Level.FINE, "WebSocket transport error", exception);
    }
}
