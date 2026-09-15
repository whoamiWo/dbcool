package com.nocobase.alert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.stereotype.Component;

/**
 * R11: 内存告警收集器(Java 端) — 对等 Python AlertCollector.
 *
 * 线程安全(基于 ConcurrentLinkedDeque + CopyOnWriteArrayList).
 * 容量超限时按插入顺序淘汰.
 *
 * 推荐用法:
 *   collector.emit("rate_limit_exceeded", "u1", Map.of("limit", 30));
 *   collector.ack(eventId, "admin");
 */
@Component
public class AlertCollector {

    private static final Logger log = Logger.getLogger(AlertCollector.class.getName());

    private final int maxEvents;
    private final ConcurrentLinkedDeque<AlertEvent> events = new ConcurrentLinkedDeque<>();
    /** 监听器列表(emit 时同步触发). */
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public AlertCollector() {
        this(1000);
    }

    public AlertCollector(int maxEvents) {
        this.maxEvents = maxEvents;
    }

    /** 添加监听器 — emit 后回调(用于 WebSocket 推送、metrics 等). */
    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public int listenerCount() {
        return listeners.size();
    }

    /** 触发一条告警. 返回事件以便调用方拿 id. */
    public AlertEvent emit(String kind, String userId, Map<String, Object> detail) {
        AlertEvent ev = new AlertEvent(kind, userId, detail);
        events.addLast(ev);
        // LRU 淘汰
        while (events.size() > maxEvents) {
            events.pollFirst();
        }
        log.warning("[alert] " + kind + " id=" + ev.id() + " user=" + userId);
        // 触发监听器(异常不影响事件本身)
        for (Listener l : listeners) {
            try {
                l.onAlert(ev);
            } catch (Exception e) {
                log.log(Level.WARNING, "alert listener failed", e);
            }
        }
        return ev;
    }

    public AlertEvent get(String eventId) {
        for (AlertEvent ev : events) {
            if (ev.id().equals(eventId)) return ev;
        }
        return null;
    }

    public boolean ack(String eventId, String by) {
        AlertEvent ev = get(eventId);
        if (ev == null) return false;
        ev.ack(by);
        log.info("[alert] ack id=" + eventId + " by=" + by);
        return true;
    }

    public boolean resolve(String eventId) {
        AlertEvent ev = get(eventId);
        if (ev == null) return false;
        ev.resolve();
        log.info("[alert] resolve id=" + eventId);
        return true;
    }

    /**
     * 查询最近事件(按 ts DESC;但内存存储按插入顺序,所以先 reverse 再 limit).
     */
    public List<AlertEvent> recent(int limit, boolean includeResolved) {
        List<AlertEvent> snapshot = new ArrayList<>(events);
        Collections.reverse(snapshot);
        if (!includeResolved) {
            snapshot.removeIf(AlertEvent::resolved);
        }
        if (snapshot.size() > limit) {
            return snapshot.subList(0, limit);
        }
        return snapshot;
    }

    public Map<String, Integer> countByKind(boolean includeResolved) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (AlertEvent ev : events) {
            if (!includeResolved && ev.resolved()) continue;
            out.merge(ev.kind(), 1, Integer::sum);
        }
        return out;
    }

    public int unresolvedCount() {
        int n = 0;
        for (AlertEvent ev : events) if (!ev.resolved()) n++;
        return n;
    }

    public int size() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }

    public Collection<AlertEvent> all() {
        return new ArrayList<>(events);
    }

    /** 监听器接口. */
    public interface Listener {
        void onAlert(AlertEvent event);
    }
}
