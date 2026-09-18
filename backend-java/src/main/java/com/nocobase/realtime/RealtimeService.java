package com.nocobase.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时协作服务 — CRDT 增量广播(Week 44 接真)。
 *
 * <p><b>模型</b>:服务端不解析文档内容,仅做「增量转发」——
 * 客户端(Yjs)把二进制 update 编码为 Base64 发来,服务端按文档房间
 * 广播给同房间其他成员。文档一致性由 CRDT(Yjs)在客户端保证,
 * 因此服务端无需实现 OT/CRDT 变换逻辑,也避免了多实例状态同步问题。
 *
 * <p><b>通道</b>:经既有 STOMP 通道(端点 {@code /ws/im})分发,
 * destination 带租户段(见 {@link StompDestinations}),
 * 由 {@code TenantSubscriptionInterceptor} 保证跨租户隔离。
 *
 * <p><b>降级</b>:广播失败(如测试环境无 WebSocket broker)仅告警,不抛异常。
 */
@Service
public class RealtimeService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);

    /** docId -> userId -> sessionId */
    private final Map<String, Map<UUID, String>> activeConnections = new ConcurrentHashMap<>();

    /** docId -> 单调递增版本号(用于客户端丢弃乱序/重复更新)。 */
    private final Map<String, Long> docVersions = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messagingTemplate;

    public RealtimeService(@Lazy SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // ============================================================
    //  协作房间
    // ============================================================

    /** 用户加入协作房间。 */
    public void joinRoom(String docId, UUID userId, String sessionId) {
        activeConnections.computeIfAbsent(docId, k -> new ConcurrentHashMap<>())
                .put(userId, sessionId);
        log.info("[Realtime] join doc={} user={} (total {})",
                docId, userId, activeConnections.get(docId).size());
    }

    /** 用户离开协作房间。 */
    public void leaveRoom(String docId, UUID userId) {
        Map<UUID, String> room = activeConnections.get(docId);
        if (room == null) return;
        room.remove(userId);
        log.info("[Realtime] leave doc={} user={} (remaining {})", docId, userId, room.size());
        if (room.isEmpty()) {
            activeConnections.remove(docId);
            docVersions.remove(docId);
        }
    }

    /** 房间内在线用户 ID。 */
    public List<UUID> getRoomUsers(String docId) {
        Map<UUID, String> room = activeConnections.get(docId);
        return room == null ? List.of() : List.copyOf(room.keySet());
    }

    /** 房间活跃人数。 */
    public int getActiveUsers(String docId) {
        return activeConnections.getOrDefault(docId, Map.of()).size();
    }

    public boolean isInRoom(String docId, UUID userId) {
        return activeConnections.getOrDefault(docId, Map.of()).containsKey(userId);
    }

    public Set<String> getActiveDocuments() {
        return Collections.unmodifiableSet(activeConnections.keySet());
    }

    // ============================================================
    //  CRDT 增量广播
    // ============================================================

    /**
     * 应用一条 CRDT 增量并广播给房间其他成员。
     *
     * @param docId        文档 ID(Wiki 页面 ID 等)
     * @param tenantId     租户 ID(决定 destination 租户段)
     * @param userId       发起更新的用户
     * @param updateBase64 Yjs update 的 Base64 编码
     * @return 本次更新被分配的版本号
     */
    public long applyUpdate(String docId, String tenantId, UUID userId, String updateBase64) {
        long version = docVersions.merge(docId, 1L, Long::sum);
        Map<String, Object> payload = Map.of(
                "docId", docId,
                "userId", userId == null ? "" : userId.toString(),
                "update", updateBase64 == null ? "" : updateBase64,
                "version", version,
                "ts", Instant.now().toString()
        );
        broadcast(StompDestinations.collabTopic(tenantId, docId), payload);
        return version;
    }

    /** 广播任意事件到文档房间(如光标位置 / 在线状态)。 */
    public void broadcastUpdate(String docId, String tenantId, UUID senderId,
                                Map<String, Object> update) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(update);
        payload.put("docId", docId);
        payload.put("userId", senderId == null ? "" : senderId.toString());
        payload.put("ts", Instant.now().toString());
        broadcast(StompDestinations.collabTopic(tenantId, docId), payload);
    }

    /** 实际发送;失败仅告警(降级),不阻断业务。 */
    private void broadcast(String destination, Map<String, Object> payload) {
        if (messagingTemplate == null) {
            log.debug("[Realtime] 无 SimpMessagingTemplate,跳过广播: {}", destination);
            return;
        }
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.warn("[Realtime] 广播失败(降级): dest={}, err={}", destination, e.getMessage());
        }
    }
}
