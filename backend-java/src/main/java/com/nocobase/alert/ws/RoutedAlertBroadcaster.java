package com.nocobase.alert.ws;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.nocobase.alert.AlertEvent;

/**
 * R11: 路由 Listener — AlertCollector 触发后,按订阅路由广播.
 *
 * 注意: 实际订阅路由由 AlertBroadcaster.broadcastToSubscribers(kind, event) 完成.
 * 本类仅作为 Listener 接口适配层,把 AlertCollector.Listener.onAlert(ev)
 * 翻译为 broadcaster.broadcastToSubscribers(ev.kind(), ev).
 */
@Component
@Primary
public class RoutedAlertBroadcaster {

    private final AlertBroadcaster broadcaster;

    public RoutedAlertBroadcaster(AlertBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /** 给 AlertBroadcastInitializer 注册用. */
    public void onAlert(AlertEvent ev) {
        broadcaster.broadcastToSubscribers(ev.kind(), ev);
    }
}
