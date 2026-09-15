package com.nocobase.alert.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nocobase.alert.AlertCollector;
import com.nocobase.alert.AlertEvent;

/** R11: AlertBroadcastInitializer 集成测试. */
class AlertBroadcastInitializerTest {

    @Test
    void onAppReadyRegistersRoutedBroadcasterAsListener() {
        AlertCollector collector = new AlertCollector();
        AlertBroadcaster broadcaster = new AlertBroadcaster(mock(com.nocobase.alert.AlertStore.class));
        RoutedAlertBroadcaster routed = new RoutedAlertBroadcaster(broadcaster);

        assertEquals(0, collector.listenerCount());

        new AlertBroadcastInitializer(collector, routed).onAppReady();

        assertEquals(1, collector.listenerCount());
    }

    @Test
    void emitTriggersRoutedBroadcast() {
        AlertCollector collector = new AlertCollector();
        AlertBroadcaster broadcaster = new AlertBroadcaster(mock(com.nocobase.alert.AlertStore.class));
        RoutedAlertBroadcaster routed = new RoutedAlertBroadcaster(broadcaster);
        new AlertBroadcastInitializer(collector, routed).onAppReady();

        AlertEvent ev = collector.emit("test", "u1", Map.of("k", "v"));
        assertEquals("test", ev.kind());
        assertEquals(1, collector.size());
    }
}
