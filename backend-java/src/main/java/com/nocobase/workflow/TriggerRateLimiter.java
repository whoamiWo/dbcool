package com.nocobase.workflow;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 触发器频率限制器(Week 41 D4a — 报告 C-R04 死循环防护).
 *
 * <p>同一 record_id 在 1 分钟内最多触发 N 次(N 默认 5)。
 * 超出则阻断并记录日志。
 *
 * <p>用 ConcurrentHashMap + 滑动窗口简单实现(Week 41 单实例足够)。
 * 多实例部署(Week 42+)需要 Redis 共享计数。
 */
@Component
public class TriggerRateLimiter {

    /** 窗口大小(秒) */
    private static final long WINDOW_SECONDS = 60;

    /** 窗口内最大触发次数 */
    private static final int MAX_TRIGGERS = 5;

    /** key = workflowId + "|" + recordId, value = 时间戳队列 */
    private final Map<String, java.util.Deque<Instant>> hits = new ConcurrentHashMap<>();

    /** 检查是否允许触发(返回 true=允许,false=被限制)。 */
    public boolean allowTrigger(String workflowId, String recordId) {
        if (workflowId == null || recordId == null) return true; // 无 recordId 不限
        String key = workflowId + "|" + recordId;
        java.util.Deque<Instant> window = hits.computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());

        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(WINDOW_SECONDS);

        // 清掉窗口外的旧时间戳
        while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
            window.pollFirst();
        }

        if (window.size() >= MAX_TRIGGERS) {
            return false;
        }
        window.addLast(now);
        return true;
    }

    /** 测试 / 清理用:清空所有计数。 */
    public void reset() {
        hits.clear();
    }

    /** 测试 / 监控用:当前 key 的触发次数。 */
    public int countFor(String workflowId, String recordId) {
        String key = workflowId + "|" + recordId;
        java.util.Deque<Instant> window = hits.get(key);
        return window == null ? 0 : window.size();
    }
}
