package com.nocobase.alert.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nocobase.alert.AlertCollector;
import com.nocobase.alert.AlertEvent;
import com.nocobase.realtime.RedisStompBridge;
import com.nocobase.tenant.TenantContext;

/** 任务 6:告警接入统一总线的转发器测试。 */
class AlertStompForwarderTest {

    @Test
    void constructionRegistersAsListener() {
        AlertCollector collector = new AlertCollector();
        RedisStompBridge bridge = mock(RedisStompBridge.class);

        new AlertStompForwarder(collector, bridge);

        assertEquals(1, collector.listenerCount());
    }

    @Test
    void emitForwardsToBusWithTenantScopedTopic() {
        AlertCollector collector = new AlertCollector();
        RedisStompBridge bridge = mock(RedisStompBridge.class);
        new AlertStompForwarder(collector, bridge);

        AlertEvent ev = collector.emit("rate_limit_exceeded", "u1", Map.of("limit", 30));

        // 未设置 TenantContext → 兜底默认租户,目标必须带租户段
        String expectedTopic = "/topic/t-" + TenantContext.DEFAULT_TENANT + ".alerts";
        verify(bridge).broadcast(eq(expectedTopic), anyMap());
    }

    @Test
    void forwardedPayloadCarriesEventFields() {
        AlertCollector collector = new AlertCollector();
        RedisStompBridge bridge = mock(RedisStompBridge.class);
        new AlertStompForwarder(collector, bridge);

        AlertEvent ev = collector.emit("test_kind", "u9", Map.of("k", "v"));

        // payload 直接用 AlertEvent.toMap(),含 id/kind/user_id/detail 等字段
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(bridge).broadcast(eq("/topic/t-" + TenantContext.DEFAULT_TENANT + ".alerts"),
                captor.capture());

        Map<String, Object> payload = captor.getValue();
        assertEquals(ev.id(), payload.get("id"));
        assertEquals("test_kind", payload.get("kind"));
        assertEquals("u9", payload.get("user_id"));
    }

    @Test
    void nullEventIsIgnored() {
        RedisStompBridge bridge = mock(RedisStompBridge.class);
        AlertStompForwarder forwarder = new AlertStompForwarder(new AlertCollector(), bridge);

        forwarder.onAlert(null);

        verify(bridge, never()).broadcast(org.mockito.ArgumentMatchers.anyString(), anyMap());
    }
}
