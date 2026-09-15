package com.nocobase.alert.ws;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.alert.AlertEvent;
import com.nocobase.alert.AlertStore;

/**
 * R11: WebSocket 广播器(Java),支持按订阅路由.
 *
 * 路由策略:
 * - session 的 attributes 中存 `user_id`(由 Handler 在握手时设置)
 * - `broadcastToSubscribers(kind, event)` — 只推送给订阅了该 kind 的用户的连接
 *   + 无 user_id 的连接(管理员/默认接收所有)
 * - `broadcastToUser(userId, event)` — 只推送给指定用户
 * - `broadcast(event)` — 兜底:推送给所有连接(保留向后兼容)
 *
 * 死连接在 sendMessage 抛 IOException 时自动清理.
 */
@Component
public class AlertBroadcaster {

    public static final String ATTR_USER_ID = "user_id";

    private static final Logger log = Logger.getLogger(AlertBroadcaster.class.getName());

    private final Set<WebSocketSession> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ObjectMapper mapper = new ObjectMapper();
    private final AlertStore store;

    /** 统计:成功推送 / 跳过(无订阅者) / 失败. */
    private long deliveredCount = 0;
    private long skippedNoSubscriberCount = 0;
    private long failedCount = 0;

    public AlertBroadcaster(AlertStore store) {
        this.store = store;
    }

    /** 测试用无参构造(用占位 AlertStore). */
    public AlertBroadcaster() {
        this.store = null;
    }

    public void addSession(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket 客户端已连接 user_id=" + userIdOf(session) + " (总数: " + sessions.size() + ")");
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session);
        log.info("WebSocket 客户端已断开 user_id=" + userIdOf(session) + " (剩余: " + sessions.size() + ")");
    }

    public int connectionCount() {
        return sessions.size();
    }

    /** 通用广播:推送给所有连接(向后兼容). */
    public void broadcast(AlertEvent event) {
        if (sessions.isEmpty()) return;
        TextMessage msg = serialize(event);
        if (msg == null) return;
        Set<WebSocketSession> snapshot = new HashSet<>(sessions);
        for (WebSocketSession s : snapshot) {
            sendOrDrop(s, msg);
        }
    }

    /**
     * 按订阅路由: 只推送给
     * 1. 订阅了该 event.kind 的用户连接
     * 2. 无 user_id 的连接(管理员/默认)
     *
     * @return 实际推送的会话数
     */
    public int broadcastToSubscribers(String kind, AlertEvent event) {
        if (sessions.isEmpty()) return 0;
        TextMessage msg = serialize(event);
        if (msg == null) return 0;

        java.util.List<String> subscribers = java.util.Collections.emptyList();
        try {
            if (store != null) {
                subscribers = store.subscribersFor(kind);
            }
        } catch (Exception e) {
            log.log(Level.FINE, "查询订阅者失败 kind=" + kind, e);
        }

        Set<WebSocketSession> snapshot = new HashSet<>(sessions);
        int delivered = 0;
        for (WebSocketSession s : snapshot) {
            String uid = userIdOf(s);
            if (uid == null) {
                // 无 user_id(管理员/默认) — 总是收
                if (sendOrDrop(s, msg)) delivered++;
            } else if (subscribers.contains(uid)) {
                if (sendOrDrop(s, msg)) delivered++;
            }
            // else: 该用户没订阅此 kind,跳过
        }

        if (delivered == 0) {
            skippedNoSubscriberCount++;
        } else {
            deliveredCount++;
        }
        return delivered;
    }

    /** 只推送给指定用户的所有连接. */
    public int broadcastToUser(String userId, AlertEvent event) {
        if (sessions.isEmpty() || userId == null) return 0;
        TextMessage msg = serialize(event);
        if (msg == null) return 0;
        Set<WebSocketSession> snapshot = new HashSet<>(sessions);
        int delivered = 0;
        for (WebSocketSession s : snapshot) {
            if (userId.equals(userIdOf(s))) {
                if (sendOrDrop(s, msg)) delivered++;
            }
        }
        return delivered;
    }

    private TextMessage serialize(AlertEvent event) {
        try {
            return new TextMessage(mapper.writeValueAsString(event.toMap()));
        } catch (Exception e) {
            log.log(Level.WARNING, "序列化告警事件失败", e);
            return null;
        }
    }

    /** 发送消息;成功返回 true,失败(连接已关/抛错)清理并返回 false. */
    private boolean sendOrDrop(WebSocketSession s, TextMessage msg) {
        try {
            if (s.isOpen()) {
                synchronized (s) {
                    s.sendMessage(msg);
                }
                return true;
            } else {
                sessions.remove(s);
                return false;
            }
        } catch (IOException e) {
            log.log(Level.FINE, "send failed, removing dead session: " + e.getMessage());
            sessions.remove(s);
            failedCount++;
            return false;
        }
    }

    private static String userIdOf(WebSocketSession s) {
        if (s == null) return null;
        Object v = s.getAttributes().get(ATTR_USER_ID);
        return v == null ? null : v.toString();
    }

    public long getDeliveredCount() {
        return deliveredCount;
    }

    public long getSkippedNoSubscriberCount() {
        return skippedNoSubscriberCount;
    }

    public long getFailedCount() {
        return failedCount;
    }
}
