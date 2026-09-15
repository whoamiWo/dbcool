package com.nocobase.alert.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import com.nocobase.alert.AlertEvent;
import com.nocobase.alert.AlertStore;

/** R11: AlertBroadcaster 按订阅路由测试. */
class AlertBroadcasterRoutedTest {

    private AlertStore store;
    private AlertBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        store = mock(AlertStore.class);
        broadcaster = new AlertBroadcaster(store);
    }

    @Test
    void broadcastToSubscribersOnlyDeliversToSubscribedUsers() throws Exception {
        WebSocketSession alice = mockSessionWithUserId("alice");
        WebSocketSession bob = mockSessionWithUserId("bob");
        WebSocketSession admin = mockSessionWithUserId(null);  // 管理员/无 user_id
        broadcaster.addSession(alice);
        broadcaster.addSession(bob);
        broadcaster.addSession(admin);

        // alice + admin 订阅 rate_limit_exceeded,bob 订阅 quota_exceeded
        when(store.subscribersFor("rate_limit_exceeded")).thenReturn(List.of("alice"));
        when(store.subscribersFor("quota_exceeded")).thenReturn(List.of("bob"));

        AlertEvent rateEv = new AlertEvent("rate_limit_exceeded", "u1", Map.of("limit", 30));
        int delivered = broadcaster.broadcastToSubscribers(rateEv.kind(), rateEv);

        // 应推送给 alice + admin,共 2 个
        assertEquals(2, delivered);
        verify(alice, times(1)).sendMessage(any(WebSocketMessage.class));
        verify(admin, times(1)).sendMessage(any(WebSocketMessage.class));
        verify(bob, times(0)).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void broadcastToSubscribersNoSubscribersNoDelivery() throws Exception {
        WebSocketSession alice = mockSessionWithUserId("alice");
        broadcaster.addSession(alice);
        when(store.subscribersFor("rate_limit_exceeded")).thenReturn(List.of());

        AlertEvent ev = new AlertEvent("rate_limit_exceeded", "u1", null);
        int delivered = broadcaster.broadcastToSubscribers(ev.kind(), ev);

        assertEquals(0, delivered);
        verify(alice, times(0)).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void broadcastToUserDeliversOnlyToThatUser() throws Exception {
        WebSocketSession alice1 = mockSessionWithUserId("alice");
        WebSocketSession alice2 = mockSessionWithUserId("alice");
        WebSocketSession bob = mockSessionWithUserId("bob");
        broadcaster.addSession(alice1);
        broadcaster.addSession(alice2);
        broadcaster.addSession(bob);

        AlertEvent ev = new AlertEvent("test", null, null);
        int delivered = broadcaster.broadcastToUser("alice", ev);

        assertEquals(2, delivered);  // alice 有 2 个连接
        verify(alice1, times(1)).sendMessage(any(WebSocketMessage.class));
        verify(alice2, times(1)).sendMessage(any(WebSocketMessage.class));
        verify(bob, times(0)).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void broadcastToUserWithNoConnectionReturnsZero() {
        AlertEvent ev = new AlertEvent("test", null, null);
        int delivered = broadcaster.broadcastToUser("ghost", ev);
        assertEquals(0, delivered);
    }

    @Test
    void broadcastDeliversToAll() throws Exception {
        WebSocketSession alice = mockSessionWithUserId("alice");
        WebSocketSession bob = mockSessionWithUserId("bob");
        broadcaster.addSession(alice);
        broadcaster.addSession(bob);

        AlertEvent ev = new AlertEvent("test", null, null);
        broadcaster.broadcast(ev);  // 通用 broadcast,不管订阅

        verify(alice, times(1)).sendMessage(any(WebSocketMessage.class));
        verify(bob, times(1)).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void broadcastToSubscribersFailingSessionCleaned() throws Exception {
        WebSocketSession failing = mockSessionWithUserId("alice");
        WebSocketSession ok = mockSessionWithUserId("admin");
        when(failing.isOpen()).thenReturn(true);
        when(ok.isOpen()).thenReturn(true);
        doThrow(new IOException("send failed")).when(failing).sendMessage(any(WebSocketMessage.class));

        broadcaster.addSession(failing);
        broadcaster.addSession(ok);

        when(store.subscribersFor("test")).thenReturn(List.of("alice"));
        AlertEvent ev = new AlertEvent("test", null, null);
        broadcaster.broadcastToSubscribers(ev.kind(), ev);

        // failing 被清理
        assertEquals(1, broadcaster.connectionCount());
        assertEquals(1, broadcaster.getFailedCount());
    }

    @Test
    void statsTrackDeliveriesAndSkips() throws Exception {
        WebSocketSession alice = mockSessionWithUserId("alice");
        WebSocketSession bob = mockSessionWithUserId("bob");
        broadcaster.addSession(alice);
        broadcaster.addSession(bob);

        // 场景 1: alice 订阅,推送成功
        when(store.subscribersFor("a")).thenReturn(List.of("alice"));
        broadcaster.broadcastToSubscribers("a", new AlertEvent("a", null, null));
        assertEquals(1, broadcaster.getDeliveredCount());
        assertEquals(0, broadcaster.getSkippedNoSubscriberCount());

        // 场景 2: 无人订阅,跳过
        when(store.subscribersFor("b")).thenReturn(List.of());
        broadcaster.broadcastToSubscribers("b", new AlertEvent("b", null, null));
        assertEquals(1, broadcaster.getDeliveredCount());
        assertEquals(1, broadcaster.getSkippedNoSubscriberCount());
    }

    // ── Handler extractUserId 测试 ─────────────────────────
    @Test
    void extractUserIdFromQueryString() throws Exception {
        var uri = new java.net.URI("ws://localhost/api/ws/alerts?user_id=alice");
        assertEquals("alice", AlertWebSocketHandler.extractUserId(uri));
    }

    @Test
    void extractUserIdWithOtherParams() throws Exception {
        var uri = new java.net.URI("ws://localhost/api/ws/alerts?foo=bar&user_id=bob&x=1");
        assertEquals("bob", AlertWebSocketHandler.extractUserId(uri));
    }

    @Test
    void extractUserIdMissing() throws Exception {
        var uri = new java.net.URI("ws://localhost/api/ws/alerts");
        assertNull(AlertWebSocketHandler.extractUserId(uri));
    }

    @Test
    void extractUserIdFromNullUri() {
        assertNull(AlertWebSocketHandler.extractUserId(null));
    }

    @Test
    void extractUserIdEmptyValue() throws Exception {
        var uri = new java.net.URI("ws://localhost/api/ws/alerts?user_id=");
        // 空值也返回(让 Handler 自己过滤)
        assertEquals("", AlertWebSocketHandler.extractUserId(uri));
    }

    // ── helpers ──────────────────────────────────────────────
    private WebSocketSession mockSessionWithUserId(String userId) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        if (userId != null) attrs.put(AlertBroadcaster.ATTR_USER_ID, userId);
        when(s.getAttributes()).thenReturn(attrs);
        when(s.isOpen()).thenReturn(true);
        return s;
    }
}
