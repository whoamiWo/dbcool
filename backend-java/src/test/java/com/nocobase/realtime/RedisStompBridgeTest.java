package com.nocobase.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** 跨实例桥接:本地投递 + Redis 发布 + 回环丢弃。 */
class RedisStompBridgeTest {

    private SimpMessagingTemplate messaging;
    private StringRedisTemplate redis;
    private RedisStompBridge bridge;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        redis = mock(StringRedisTemplate.class);
        bridge = new RedisStompBridge(messaging, redis, new ObjectMapper());
    }

    @Test
    void broadcast_sendsLocalAndPublishesToRedis() {
        bridge.broadcast("/topic/t-tenant_default.channel.c1", Map.of("hello", "world"));

        verify(messaging).convertAndSend(eq("/topic/t-tenant_default.channel.c1"), any(Object.class));
        verify(redis).convertAndSend(eq(RedisStompBridge.REDIS_CHANNEL), anyString());
    }

    @Test
    void broadcast_nullArgs_areIgnored() {
        bridge.broadcast(null, Map.of());
        bridge.broadcast("/topic/x", null);

        verifyNoInteractions(messaging);
        verifyNoInteractions(redis);
    }

    @Test
    void onRemoteMessage_fromOtherInstance_forwardsLocally() {
        String raw = "{\"senderId\":\"other-instance\","
                + "\"destination\":\"/topic/t-tenant_default.channel.c1\","
                + "\"payload\":{\"a\":1}}";

        assertThat(bridge.onRemoteMessage(raw)).isTrue();
        verify(messaging).convertAndSend(eq("/topic/t-tenant_default.channel.c1"), any(Object.class));
    }

    @Test
    void onRemoteMessage_ignoresLoopbackFromSelf() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        bridge.broadcast("/topic/t-t.c1", Map.of("x", 1));
        verify(redis).convertAndSend(eq(RedisStompBridge.REDIS_CHANNEL), captor.capture());

        // 回放自己发出的消息 → 必须丢弃,否则会重复投递
        assertThat(bridge.onRemoteMessage(captor.getValue())).isFalse();
    }

    @Test
    void onRemoteMessage_invalidInput_returnsFalse() {
        assertThat(bridge.onRemoteMessage("not-json")).isFalse();
        assertThat(bridge.onRemoteMessage("")).isFalse();
        assertThat(bridge.onRemoteMessage(null)).isFalse();
    }
}
