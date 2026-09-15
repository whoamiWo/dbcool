package com.nocobase.alert.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import com.nocobase.alert.AlertEvent;

/** R11: AlertBroadcaster 单元测试 (Mockito). */
class AlertBroadcasterTest {

    private AlertBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new AlertBroadcaster();
    }

    @Test
    void addAndRemoveSession() {
        WebSocketSession s1 = mock(WebSocketSession.class);
        WebSocketSession s2 = mock(WebSocketSession.class);
        broadcaster.addSession(s1);
        broadcaster.addSession(s2);
        assertEquals(2, broadcaster.connectionCount());
        broadcaster.removeSession(s1);
        assertEquals(1, broadcaster.connectionCount());
        broadcaster.removeSession(s2);
        assertEquals(0, broadcaster.connectionCount());
    }

    @Test
    void broadcastDeliversToAllSessions() throws Exception {
        WebSocketSession s1 = mock(WebSocketSession.class);
        WebSocketSession s2 = mock(WebSocketSession.class);
        when(s1.isOpen()).thenReturn(true);
        when(s2.isOpen()).thenReturn(true);
        broadcaster.addSession(s1);
        broadcaster.addSession(s2);

        AlertEvent ev = new AlertEvent("rate_limit_exceeded", "u1", Map.of("limit", 30));
        broadcaster.broadcast(ev);

        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(s1, times(1)).sendMessage(cap.capture());
        verify(s2, times(1)).sendMessage(cap.capture());
        String payload = cap.getValue().getPayload();
        assertTrue(payload.contains("rate_limit_exceeded"));
        assertTrue(payload.contains("u1"));
        assertTrue(payload.contains("\"limit\":30"));
    }

    @Test
    void broadcastRemovesClosedSessions() throws Exception {
        WebSocketSession alive = mock(WebSocketSession.class);
        WebSocketSession closed = mock(WebSocketSession.class);
        when(alive.isOpen()).thenReturn(true);
        when(closed.isOpen()).thenReturn(false);  // 已关闭
        broadcaster.addSession(alive);
        broadcaster.addSession(closed);
        assertEquals(2, broadcaster.connectionCount());

        broadcaster.broadcast(new AlertEvent("test", null, null));

        assertEquals(1, broadcaster.connectionCount());  // closed 被清理
        verify(alive, times(1)).sendMessage(any(WebSocketMessage.class));
        verify(closed, never()).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void broadcastEmptyDoesNothing() {
        // 不抛错
        broadcaster.broadcast(new AlertEvent("test", null, null));
        assertEquals(0, broadcaster.connectionCount());
    }

    @Test
    void broadcastRemovesFailingSession() throws Exception {
        WebSocketSession failing = mock(WebSocketSession.class);
        WebSocketSession ok = mock(WebSocketSession.class);
        when(failing.isOpen()).thenReturn(true);
        when(ok.isOpen()).thenReturn(true);
        doThrow(new IOException("send failed")).when(failing).sendMessage(any(WebSocketMessage.class));

        broadcaster.addSession(failing);
        broadcaster.addSession(ok);

        broadcaster.broadcast(new AlertEvent("test", null, null));

        // failing 被清理
        assertEquals(1, broadcaster.connectionCount());
        verify(ok, times(1)).sendMessage(any(WebSocketMessage.class));
    }
}
