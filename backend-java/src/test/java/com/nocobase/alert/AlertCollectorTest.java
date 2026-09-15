package com.nocobase.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** R11: AlertCollector (Java) 单元测试. */
class AlertCollectorTest {

    private AlertCollector collector;

    @BeforeEach
    void setUp() {
        collector = new AlertCollector(100);
    }

    @Test
    void emitReturnsEventWithId() {
        var ev = collector.emit("rate_limit_exceeded", "u1", Map.of("limit", 30));
        assertNotNull(ev.id());
        assertEquals("rate_limit_exceeded", ev.kind());
        assertEquals("u1", ev.userId());
        assertEquals(30, ev.detail().get("limit"));
        assertEquals(1, collector.size());
    }

    @Test
    void emitMultipleAccumulates() {
        collector.emit("a", null, null);
        collector.emit("b", null, null);
        collector.emit("c", null, null);
        assertEquals(3, collector.size());
    }

    @Test
    void maxEventsEvictsOldest() {
        AlertCollector small = new AlertCollector(3);
        small.emit("a", null, null);
        small.emit("b", null, null);
        small.emit("c", null, null);
        small.emit("d", null, null);
        // a 应被淘汰
        assertEquals(3, small.size());
    }

    @Test
    void ackSetsFields() {
        var ev = collector.emit("test", "u1", null);
        assertTrue(collector.ack(ev.id(), "admin"));
        var loaded = collector.get(ev.id());
        assertTrue(loaded.acked());
        assertEquals("admin", loaded.ackedBy());
        assertNotNull(loaded.ackedAt());
    }

    @Test
    void ackUnknownReturnsFalse() {
        assertFalse(collector.ack("nonexistent", null));
    }

    @Test
    void resolveSetsFields() {
        var ev = collector.emit("test", null, null);
        assertTrue(collector.resolve(ev.id()));
        var loaded = collector.get(ev.id());
        assertTrue(loaded.resolved());
        assertNotNull(loaded.resolvedAt());
    }

    @Test
    void resolveUnknownReturnsFalse() {
        assertFalse(collector.resolve("nonexistent"));
    }

    @Test
    void getUnknownReturnsNull() {
        assertNull(collector.get("nope"));
    }

    @Test
    void recentRespectsLimit() {
        for (int i = 0; i < 10; i++) {
            collector.emit("k" + i, null, null);
        }
        var recent = collector.recent(3, true);
        assertEquals(3, recent.size());
    }

    @Test
    void recentFiltersResolved() {
        var ev1 = collector.emit("a", null, null);
        collector.emit("b", null, null);
        collector.resolve(ev1.id());
        var all = collector.recent(10, true);
        var unresolved = collector.recent(10, false);
        assertEquals(2, all.size());
        assertEquals(1, unresolved.size());
        assertEquals("b", unresolved.get(0).kind());
    }

    @Test
    void countByKind() {
        collector.emit("a", null, null);
        collector.emit("a", null, null);
        collector.emit("b", null, null);
        var counts = collector.countByKind(true);
        assertEquals(2, counts.get("a"));
        assertEquals(1, counts.get("b"));
    }

    @Test
    void countByKindExcludesResolved() {
        var ev1 = collector.emit("a", null, null);
        collector.emit("a", null, null);
        collector.resolve(ev1.id());
        var counts = collector.countByKind(false);
        assertEquals(1, counts.get("a"));
    }

    @Test
    void unresolvedCount() {
        var ev1 = collector.emit("a", null, null);
        collector.emit("a", null, null);
        assertEquals(2, collector.unresolvedCount());
        collector.resolve(ev1.id());
        assertEquals(1, collector.unresolvedCount());
    }

    @Test
    void listenerTriggeredOnEmit() {
        var received = new ArrayList<AlertEvent>();
        collector.addListener(received::add);
        var ev = collector.emit("test", "u1", null);
        assertEquals(1, received.size());
        assertEquals(ev.id(), received.get(0).id());
    }

    @Test
    void listenerRemovedNoLongerFires() {
        AtomicInteger count = new AtomicInteger();
        var listener = new AlertCollector.Listener() {
            @Override public void onAlert(AlertEvent event) { count.incrementAndGet(); }
        };
        collector.addListener(listener);
        collector.emit("a", null, null);
        collector.removeListener(listener);
        collector.emit("b", null, null);
        assertEquals(1, count.get());
    }

    @Test
    void failingListenerDoesNotBreakOthers() {
        AtomicInteger goodCount = new AtomicInteger();
        // failing listener 只在 kind="bad" 时抛
        collector.addListener(event -> {
            if (event.kind().equals("bad")) throw new RuntimeException("boom");
        });
        // good listener 永远正常
        collector.addListener(event -> goodCount.incrementAndGet());
        collector.emit("bad", null, null);  // good listener 仍能收到(已抛的 listener 不影响后续)
        collector.emit("good", null, null);
        // good 收到了 2 次(bad + good),关键是没有中断
        assertEquals(2, goodCount.get());
        // emit 本身没被中断,size 应该 = 2
        assertEquals(2, collector.size());
    }

    @Test
    void clearEmptiesBuffer() {
        collector.emit("a", null, null);
        collector.emit("b", null, null);
        collector.clear();
        assertEquals(0, collector.size());
    }
}
