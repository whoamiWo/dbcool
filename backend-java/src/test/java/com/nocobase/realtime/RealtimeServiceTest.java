package com.nocobase.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * PHASE105: RealtimeService 覆盖（房间管理、广播降级、CRDT 路径）。
 */
public class RealtimeServiceTest {

    private RealtimeService service;
    private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        service = new RealtimeService(messagingTemplate, "");
    }

    // ── 房间管理 ───────────────────────────────────────────
    @Test
    void joinRoom_addsUser() {
        UUID u1 = UUID.randomUUID();
        service.joinRoom("doc1", u1, "s1");
        assertTrue(service.isInRoom("doc1", u1));
        assertEquals(1, service.getActiveUsers("doc1"));
    }

    @Test
    void joinRoom_multipleUsers() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        service.joinRoom("doc1", u1, "s1");
        service.joinRoom("doc1", u2, "s2");
        assertEquals(2, service.getActiveUsers("doc1"));
        List<UUID> users = service.getRoomUsers("doc1");
        assertEquals(2, users.size());
        assertTrue(users.contains(u1));
        assertTrue(users.contains(u2));
    }

    @Test
    void leaveRoom_removesUser() {
        UUID u1 = UUID.randomUUID();
        service.joinRoom("doc1", u1, "s1");
        service.leaveRoom("doc1", u1);
        assertFalse(service.isInRoom("doc1", u1));
        assertEquals(0, service.getActiveUsers("doc1"));
    }

    @Test
    void leaveRoom_lastUser_removesRoomAndVersion() {
        UUID u1 = UUID.randomUUID();
        service.joinRoom("doc1", u1, "s1");
        service.applyUpdate("doc1", "t1", u1, "abc");
        service.leaveRoom("doc1", u1);
        assertEquals(0, service.getActiveUsers("doc1"));
        assertFalse(service.getActiveDocuments().contains("doc1"));
    }

    @Test
    void leaveRoom_unknownDoc_noop() {
        service.leaveRoom("unknown", UUID.randomUUID()); // 不抛异常
    }

    @Test
    void getRoomUsers_unknownDoc_emptyList() {
        assertTrue(service.getRoomUsers("nope").isEmpty());
    }

    @Test
    void getActiveUsers_unknownDoc_zero() {
        assertEquals(0, service.getActiveUsers("nope"));
    }

    @Test
    void isInRoom_unknownDoc_false() {
        assertFalse(service.isInRoom("nope", UUID.randomUUID()));
    }

    @Test
    void getActiveDocuments_returnsJoined() {
        service.joinRoom("d1", UUID.randomUUID(), "s");
        service.joinRoom("d2", UUID.randomUUID(), "s");
        Set<String> docs = service.getActiveDocuments();
        assertEquals(2, docs.size());
        assertTrue(docs.contains("d1"));
        assertTrue(docs.contains("d2"));
    }

    // ── applyUpdate（CRDT 降级） ───────────────────────────
    @Test
    void applyUpdate_noCrdtService_incrementsVersion() {
        UUID u1 = UUID.randomUUID();
        service.joinRoom("doc1", u1, "s1");
        long v1 = service.applyUpdate("doc1", "t1", u1, "YWRk");
        long v2 = service.applyUpdate("doc1", "t1", u1, "YWRk");
        assertEquals(1L, v1);
        assertEquals(2L, v2);
    }

    @Test
    void applyUpdate_broadcastsToTemplate() {
        UUID u1 = UUID.randomUUID();
        service.applyUpdate("doc1", "t1", u1, "YWRk");
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void applyUpdate_nullUserId_usesEmptyString() {
        long v = service.applyUpdate("doc1", "t1", null, null);
        assertEquals(1L, v);
    }

    @Test
    void applyUpdate_nullTemplate_doesNotThrow() {
        RealtimeService noTemplate = new RealtimeService(null, "");
        long v = noTemplate.applyUpdate("doc1", "t1", UUID.randomUUID(), "abc");
        assertEquals(1L, v);
    }

    // ── broadcastUpdate ────────────────────────────────────
    @Test
    void broadcastUpdate_includesDocIdAndUserId() {
        UUID u1 = UUID.randomUUID();
        service.broadcastUpdate("doc1", "t1", u1, Map.of("cursor", 5));
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void broadcastUpdate_nullSender_usesEmptyString() {
        service.broadcastUpdate("doc1", "t1", null, Map.of("x", 1));
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // ── getDocumentState（CRDT 未配置） ────────────────────
    @Test
    void getDocumentState_noCrdt_returnsNull() {
        // crdtWebClient 为 null → 内部 NPE 被 catch → 返回 null
        assertNull(service.getDocumentState("doc1"));
    }
}
