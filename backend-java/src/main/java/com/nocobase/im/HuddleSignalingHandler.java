package com.nocobase.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Huddle WebRTC 信令处理。
 *
 * <p>基于原生 WebSocket 的信令中继，支持 offer/answer/candidate 交换。
 * 多副本部署时通过 Redis pub/sub 实现跨实例信令转发。
 *
 * <p>消息协议（JSON）：
 * <pre>
 * 请求:  {"type":"join|leave|offer|answer|candidate|state","roomId":"...","payload":{...}}
 * 响应:  {"type":"joined|left|peers|offer|answer|candidate|state|bye"}
 * </pre>
 *
 * <p>媒体流不经过应用服务器（SFU/P2P 架构），此处只转发信令。
 */
@Component
public class HuddleSignalingHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(HuddleSignalingHandler.class);

    public static final String REDIS_CHANNEL = "nocobase:huddle:signaling";

    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, String> sessionRooms = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final String instanceId = UUID.randomUUID().toString();

    public HuddleSignalingHandler(SimpMessagingTemplate messagingTemplate, StringRedisTemplate redisTemplate) {
        this.messagingTemplate = messagingTemplate;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(message.getPayload(), Map.class);
        } catch (Exception e) {
            send(session, Map.of("type", "error", "msg", "无效的 JSON"));
            return;
        }

        String type = (String) data.get("type");
        String roomId = (String) data.get("roomId");
        if (type == null || roomId == null) {
            send(session, Map.of("type", "error", "msg", "type 和 roomId 必填"));
            return;
        }

        switch (type) {
            case "join" -> handleJoin(session, roomId);
            case "leave" -> handleLeave(session, roomId);
            case "offer", "answer", "candidate", "state" -> relay(session, roomId, data);
            default -> send(session, Map.of("type", "error", "msg", "未知类型：" + type));
        }
    }

    private void handleJoin(WebSocketSession session, String roomId) throws IOException {
        Set<WebSocketSession> room = rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
        room.add(session);
        sessionRooms.put(session, roomId);

        // 通知本机已有成员有新成员加入
        for (WebSocketSession peer : room) {
            if (peer.isOpen() && !peer.equals(session)) {
                send(peer, Map.of("type", "peer-joined",
                        "peerId", sessionId(session)));
            }
        }
        send(session, Map.of("type", "joined", "roomId", roomId,
                "peerCount", room.size(), "sessionId", sessionId(session)));

        // 发布到 Redis，让其他实例知道有新成员加入（排除自己）
        publishToRedis(roomId, session.getId(), Map.of("type", "peer-joined",
                "peerId", sessionId(session)));
    }

    private void handleLeave(WebSocketSession session, String roomId) throws IOException {
        // 先广播 peer-left（包括 Redis），再移除
        Set<WebSocketSession> room = rooms.get(roomId);
        if (room != null) {
            broadcastOthers(roomId, session, Map.of("type", "peer-left",
                    "peerId", sessionId(session)));
        }
        removeFromRoom(session, roomId);
        send(session, Map.of("type", "left", "roomId", roomId));
    }

    private void relay(WebSocketSession session, String roomId, Map<String, Object> data) throws IOException {
        Set<WebSocketSession> room = rooms.get(roomId);
        if (room == null) {
            send(session, Map.of("type", "error", "msg", "未加入房间"));
            return;
        }
        Map<String, Object> forwarded = new HashMap<>(data);
        forwarded.put("from", sessionId(session));
        broadcastOthers(roomId, session, forwarded);
    }

    private void broadcastOthers(String roomId, WebSocketSession exclude, Map<String, Object> msg) throws IOException {
        Set<WebSocketSession> room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        for (WebSocketSession peer : room) {
            if (peer.isOpen() && !peer.equals(exclude)) {
                send(peer, msg);
            }
        }
        publishToRedis(roomId, exclude.getId(), msg);
    }

    private void publishToRedis(String roomId, String fromSessionId, Map<String, Object> msg) {
        try {
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("instanceId", instanceId);
            envelope.put("fromSessionId", fromSessionId);
            envelope.put("roomId", roomId);
            envelope.put("payload", msg);
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(REDIS_CHANNEL, json);
        } catch (Exception e) {
            log.warn("[huddle] Redis 广播失败 (roomId={}): {}", roomId, e.getMessage());
        }
    }

    public void onRemoteMessage(String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            Map<String, Object> envelope = objectMapper.readValue(raw, Map.class);
            String senderInstanceId = (String) envelope.get("instanceId");
            if (senderInstanceId == null || instanceId.equals(senderInstanceId)) {
                return;
            }
            String fromSessionId = (String) envelope.get("fromSessionId");
            if (fromSessionId == null) return;
            String roomId = (String) envelope.get("roomId");
            Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
            if (roomId == null || payload == null) return;

            Set<WebSocketSession> room = rooms.get(roomId);
            if (room == null) return;

            Map<String, Object> msg = new HashMap<>(payload);
            msg.put("remoteInstanceId", senderInstanceId);

            for (WebSocketSession peer : room) {
                String peerSessionId = sessionId(peer);
                if (peer.isOpen() && !peerSessionId.equals(fromSessionId)) {
                    send(peer, msg);
                }
            }
        } catch (Exception e) {
            log.warn("[huddle] 远程消息处理失败：{}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = sessionRooms.remove(session);
        if (roomId != null) {
            // 先广播 peer-left（包括 Redis），再移除
            Set<WebSocketSession> room = rooms.get(roomId);
            if (room != null) {
                try {
                    broadcastOthers(roomId, session, Map.of("type", "peer-left",
                            "peerId", sessionId(session)));
                } catch (IOException ignored) {
                }
            }
            removeFromRoom(session, roomId);
        }
    }

    private void removeFromRoom(WebSocketSession session, String roomId) {
        Set<WebSocketSession> room = rooms.get(roomId);
        if (room != null) {
            room.remove(session);
            if (room.isEmpty()) {
                rooms.remove(roomId);
            }
        }
    }

    private String sessionId(WebSocketSession session) {
        return session.getId();
    }

    private void send(WebSocketSession session, Map<String, Object> msg) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        }
    }
}
