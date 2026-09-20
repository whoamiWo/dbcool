package com.nocobase.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Huddle WebRTC 信令处理。
 *
 * <p>基于原生 WebSocket 的信令中继，支持 offer/answer/candidate 交换。
 * 单进程内存路由（多实例部署需迁移到 Redis pub/sub，与 STOMP 桥一致）。
 *
 * <p>消息协议（JSON）：
 * <pre>
 * 请求:  {"type":"join|leave|offer|answer|candidate|state","roomId":"...","payload":{...}}
 * 响应:  {"type":"joined|left|peers|offer|answer|candidate|state|bye"}
 * </pre>
 *
 * <p>媒体流不经过应用服务器（SFU/P2P 架构），此处只转发信令。
 */
public class HuddleSignalingHandler extends TextWebSocketHandler {

    /** 房间 ID -> 参与者会话集合 */
    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    /** 会话 -> 房间 ID */
    private final Map<WebSocketSession, String> sessionRooms = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 房间注册延后到收到 join 消息
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
            default -> send(session, Map.of("type", "error", "msg", "未知类型: " + type));
        }
    }

    private void handleJoin(WebSocketSession session, String roomId) throws IOException {
        Set<WebSocketSession> room = rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
        room.add(session);
        sessionRooms.put(session, roomId);

        // 通知房间内已有参与者：新 peer 加入（触发其创建 offer）
        for (WebSocketSession peer : room) {
            if (peer.isOpen() && !peer.equals(session)) {
                send(peer, Map.of("type", "peer-joined",
                        "peerId", sessionId(session)));
            }
        }
        send(session, Map.of("type", "joined", "roomId", roomId,
                "peerCount", room.size()));
    }

    private void handleLeave(WebSocketSession session, String roomId) throws IOException {
        removeFromRoom(session, roomId);
        send(session, Map.of("type", "left", "roomId", roomId));
        broadcastOthers(roomId, session, Map.of("type", "peer-left",
                "peerId", sessionId(session)));
    }

    /** 将 offer/answer/candidate/state 转发给同房间其他参与者。 */
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
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = sessionRooms.remove(session);
        if (roomId != null) {
            removeFromRoom(session, roomId);
            try {
                broadcastOthers(roomId, session, Map.of("type", "peer-left",
                        "peerId", sessionId(session)));
            } catch (IOException ignored) {
            }
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