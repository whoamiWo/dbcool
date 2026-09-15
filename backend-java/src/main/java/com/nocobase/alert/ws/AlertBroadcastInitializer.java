package com.nocobase.alert.ws;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.nocobase.alert.AlertCollector;

/**
 * R11: 应用启动时,自动把 RoutedAlertBroadcaster 注册到 AlertCollector 的 listener.
 * 所有 AlertCollector.emit 都会通过 AlertBroadcaster 按订阅路由实时广播.
 */
@Component
public class AlertBroadcastInitializer {

    private final AlertCollector collector;
    private final RoutedAlertBroadcaster routed;

    @Autowired
    public AlertBroadcastInitializer(AlertCollector collector, RoutedAlertBroadcaster routed) {
        this.collector = collector;
        this.routed = routed;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onAppReady() {
        // 同一个 listener 实例只注册一次
        if (collector.listenerCount() == 0) {
            collector.addListener(routed::onAlert);
        }
    }
}
