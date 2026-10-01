package com.nocobase.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * S3: HuddleSignalingHandler 单测 —— 跨副本信令的回归防线。
 *
 * <p>被测对象是 {@link HuddleSignalingHandler} 本体（房间路由 + 防回声 + Redis 转发）。
 * 只 mock {@link StringRedisTemplate}（外部基础设施，非被测主路径 Service）以验证频道与内容。
 */
class HuddleSignalingHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StringRedisTemplate redisTemplate;
    private HuddleSignalingHandler handler;
    private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        messagingTemplate = Mockito.mock(SimpMessagingTemplate.class);
        handler = new HuddleSignalingHandler(messagingTemplate, redisTemplate);
    }

    private WebSocketSession session(String id) {
        WebSocketSession s = Mockito.mock(WebSocketSession.class);
        Mockito.when(s.getId()).thenReturn(id);
        Mockito.when(s.isOpen()).thenReturn(true);
        return s;
    }

    private void send(WebSocketSession s, String json) throws Exception {
        handler.handleTextMessage(s, new TextMessage(json));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> payloadsOf(WebSocketSession s) throws Exception {
        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        try {
            verify(s, Mockito.atLeast(0)).sendMessage(cap.capture());
        } catch (Exception e) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TextMessage tm : cap.getAllValues()) {
            out.add(mapper.readValue(tm.getPayload(), Map.class));
        }
        return out;
    }

    private boolean hasType(WebSocketSession s, String type) throws Exception {
        for (Map<String, Object> m : payloadsOf(s)) {
            if (type.equals(m.get("type"))) return true;
        }
        return false;
    }

    // ---------- 1. 同房间发消息：其他成员收到，发送者收不到（防回声） ----------

    @Test
    void relay_toOtherMember_receives_butSenderDoesNot() throws Exception {
        WebSocketSession a = session("A");
        WebSocketSession b = session("B");
        send(a, "{\"type\":\"join\",\"roomId\":\"r1\"}");
        send(b, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        send(a, "{\"type\":\"offer\",\"roomId\":\"r1\",\"payload\":{\"sdp\":\"v=0\"}}");

        assertThat(hasType(b, "offer")).isTrue();
        assertThat(hasType(a, "offer"))
                .as("发送者不得收到自己的 offer（防回声）")
                .isFalse();
    }

    @Test
    void relay_addsFromField() throws Exception {
        WebSocketSession a = session("A");
        WebSocketSession b = session("B");
        send(a, "{\"type\":\"join\",\"roomId\":\"r1\"}");
        send(b, "{\"type\":\"join\",\"roomId\":\"r1\"}");
        send(a, "{\"type\":\"candidate\",\"roomId\":\"r1\",\"payload\":{\"candidate\":\"c1\"}}");

        Map<String, Object> got = payloadsOf(b).stream()
                .filter(m -> "candidate".equals(m.get("type")))
                .findFirst().orElseThrow();
        assertThat(got.get("from")).isEqualTo("A");
    }

    // ---------- 2. 加入 / 离开广播 ----------

    @Test
    void join_secondMember_notifiesFirst_asPeerJoined() throws Exception {
        WebSocketSession a = session("A");
        WebSocketSession b = session("B");
        send(a, "{\"type\":\"join\",\"roomId\":\"r1\"}");
        send(b, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        assertThat(hasType(a, "peer-joined")).isTrue();
        assertThat(hasType(b, "joined")).isTrue();
    }

    @Test
    void leave_broadcastsPeerLeft_beforeRemoval() throws Exception {
        WebSocketSession a = session("A");
        WebSocketSession b = session("B");
        send(a, "{\"type\":\"join\",\"roomId\":\"r1\"}");
        send(b, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        send(b, "{\"type\":\"leave\",\"roomId\":\"r1\"}");

        assertThat(hasType(a, "peer-left")).as("离开者须在移除前广播 peer-left").isTrue();
        assertThat(hasType(b, "left")).isTrue();
    }

    @Test
    void afterConnectionClosed_broadcastsPeerLeft() throws Exception {
        WebSocketSession a = session("A");
        WebSocketSession b = session("B");
        send(a, "{\"type\":\"join\",\"roomId\":\"r1\"}");
        send(b, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        handler.afterConnectionClosed(b, CloseStatus.NORMAL);

        assertThat(hasType(a, "peer-left")).isTrue();
    }

    @Test
    void afterConnectionClosed_cleansUpSessionFromRoom() throws Exception {
        WebSocketSession a = session("A");
        WebSocketSession c = session("C");
        send(a, "{\"type\":\"join\",\"roomId\":\"r1\"}");
        send(c, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        handler.afterConnectionClosed(c, CloseStatus.NORMAL);

        // C 已不在房间：后续 A 的消息不应再投递给 C
        send(a, "{\"type\":\"offer\",\"roomId\":\"r1\",\"payload\":{\"sdp\":\"x\"}}");
        assertThat(hasType(c, "offer")).isFalse();
    }

    // ---------- 3. Redis 发布：频道与内容 ----------

    @Test
    void join_publishesPeerJoined_toRedisChannel() throws Exception {
        WebSocketSession a = session("A");
        send(a, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate)
                .convertAndSend(eq(HuddleSignalingHandler.REDIS_CHANNEL), payload.capture());

        Map<String, Object> env = mapper.readValue(payload.getValue(), Map.class);
        assertThat(env.get("roomId")).isEqualTo("r1");
        assertThat(env.get("fromSessionId")).isEqualTo("A");
        assertThat(env.get("instanceId")).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) env.get("payload");
        assertThat(inner.get("type")).isEqualTo("peer-joined");
    }

    @Test
    void relay_publishesToRedis() throws Exception {
        WebSocketSession a = session("A");
        WebSocketSession b = session("B");
        send(a, "{\"type\":\"join\",\"roomId\":\"r1\"}");  // publish 1
        send(b, "{\"type\":\"join\",\"roomId\":\"r1\"}");  // publish 2
        send(a, "{\"type\":\"offer\",\"roomId\":\"r1\",\"payload\":{\"sdp\":\"v=0\"}}");  // publish 3

        // 2 次 join + 1 次 relay = 3 次 Redis 发布
        verify(redisTemplate, times(3))
                .convertAndSend(eq(HuddleSignalingHandler.REDIS_CHANNEL), anyString());
    }

    @Test
    void leave_publishesPeerLeft_toRedis() throws Exception {
        WebSocketSession a = session("A");
        send(a, "{\"type\":\"join\",\"roomId\":\"r1\"}");
        send(a, "{\"type\":\"leave\",\"roomId\":\"r1\"}");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, times(2))
                .convertAndSend(eq(HuddleSignalingHandler.REDIS_CHANNEL), payload.capture());

        Map<String, Object> env = mapper.readValue(payload.getValue(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) env.get("payload");
        assertThat(inner.get("type")).isEqualTo("peer-left");
    }

    // ---------- 4. 收到 Redis 消息：只投本机成员，且不回发给发送方 ----------

    @Test
    void onRemoteMessage_deliversToLocalMembersOnly() throws Exception {
        WebSocketSession local = session("LOCAL");
        send(local, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        String envelope = "{\"instanceId\":\"other-instance\",\"fromSessionId\":\"REMOTE-A\","
                + "\"roomId\":\"r1\",\"payload\":{\"type\":\"offer\",\"payload\":{\"sdp\":\"remote\"}}}";
        handler.onRemoteMessage(envelope);

        assertThat(hasType(local, "offer")).isTrue();
    }

    @Test
    void onRemoteMessage_ignoresOwnInstance() throws Exception {
        WebSocketSession local = session("LOCAL");
        send(local, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        // 自 instanceId 的消息必须被忽略（防自回声）
        String ownInstanceId = getInstanceId();
        String own = "{\"instanceId\":\"" + ownInstanceId + "\","
                + "\"fromSessionId\":\"LOCAL\",\"roomId\":\"r1\","
                + "\"payload\":{\"type\":\"offer\"}}";
        handler.onRemoteMessage(own);

        assertThat(hasType(local, "offer")).isFalse();
    }

    @Test
    void onRemoteMessage_skipsSenderSession() throws Exception {
        WebSocketSession local = session("LOCAL");
        send(local, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        String envelope = "{\"instanceId\":\"other\",\"fromSessionId\":\"REMOTE-A\","
                + "\"roomId\":\"r1\",\"payload\":{\"type\":\"offer\"}}";
        handler.onRemoteMessage(envelope);
        handler.onRemoteMessage(envelope);

        // 两条都应投给本机 LOCAL（本机无 REMOTE-A）
        long offers = payloadsOf(local).stream().filter(m -> "offer".equals(m.get("type"))).count();
        assertThat(offers).isEqualTo(2L);
    }

    @Test
    void onRemoteMessage_ignoresUnknownRoom() throws Exception {
        WebSocketSession local = session("LOCAL");
        send(local, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        handler.onRemoteMessage("{\"instanceId\":\"other\",\"fromSessionId\":\"X\","
                + "\"roomId\":\"nope\",\"payload\":{\"type\":\"offer\"}}");

        assertThat(hasType(local, "offer")).isFalse();
    }

    @Test
    void onRemoteMessage_ignoresBlankAndMalformed() throws Exception {
        WebSocketSession local = session("LOCAL");
        send(local, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        handler.onRemoteMessage(null);
        handler.onRemoteMessage("");
        handler.onRemoteMessage("not-json");

        assertThat(hasType(local, "offer")).isFalse();
    }

    @Test
    void onRemoteMessage_tagsRemoteInstanceId() throws Exception {
        WebSocketSession local = session("LOCAL");
        send(local, "{\"type\":\"join\",\"roomId\":\"r1\"}");

        handler.onRemoteMessage("{\"instanceId\":\"instance-X\",\"fromSessionId\":\"R\","
                + "\"roomId\":\"r1\",\"payload\":{\"type\":\"answer\"}}");

        Map<String, Object> got = payloadsOf(local).stream()
                .filter(m -> "answer".equals(m.get("type")))
                .findFirst().orElseThrow();
        assertThat(got.get("remoteInstanceId")).isEqualTo("instance-X");
    }

    // ---------- 边界 ----------

    @Test
    void relay_withoutJoin_returnsError() throws Exception {
        WebSocketSession a = session("A");
        send(a, "{\"type\":\"offer\",\"roomId\":\"ghost\",\"payload\":{}}");
        assertThat(hasType(a, "error")).isTrue();
    }

    @Test
    void invalidJson_returnsError() throws Exception {
        WebSocketSession a = session("A");
        send(a, "not-json");
        assertThat(hasType(a, "error")).isTrue();
    }

    @Test
    void missingTypeOrRoomId_returnsError() throws Exception {
        WebSocketSession a = session("A");
        send(a, "{\"roomId\":\"r1\"}");
        send(a, "{\"type\":\"join\"}");
        long errors = payloadsOf(a).stream().filter(m -> "error".equals(m.get("type"))).count();
        assertThat(errors).isEqualTo(2L);
    }

    @Test
    void unknownType_returnsError() throws Exception {
        WebSocketSession a = session("A");
        send(a, "{\"type\":\"bogus\",\"roomId\":\"r1\"}");
        assertThat(hasType(a, "error")).isTrue();
    }

    @Test
    void join_emptyRoom_notifiesNobody() throws Exception {
        WebSocketSession a = session("A");
        send(a, "{\"type\":\"join\",\"roomId\":\"r1\"}");
        assertThat(hasType(a, "peer-joined")).isFalse();
        assertThat(hasType(a, "joined")).isTrue();
    }

    @Test
    void redisFailure_doesNotBreakLocalDelivery() throws Exception {
        Mockito.when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        WebSocketSession a = session("A");
        WebSocketSession b = session("B");
        send(a, "{\"type\":\"join\",\"roomId\":\"r1\"}");
        send(b, "{\"type\":\"join\",\"roomId\":\"r1\"}");
        send(a, "{\"type\":\"offer\",\"roomId\":\"r1\",\"payload\":{\"sdp\":\"v\"}}");

        assertThat(hasType(b, "offer")).as("Redis 故障时本机直发仍须可用").isTrue();
    }

    private String getInstanceId() throws Exception {
        java.lang.reflect.Field f = HuddleSignalingHandler.class.getDeclaredField("instanceId");
        f.setAccessible(true);
        return (String) f.get(handler);
    }
}
