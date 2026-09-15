package com.nocobase.workflow;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 嵌套触发器守卫(Week 42 D4b.2 — R08 死循环防护第 3 道).
 *
 * <p>跨实例防递归:同一 recordId 的事件链若 A→B→C→A 形成环,
 * 单纯 recordId-level 限流可能放过"A→C→B→A"这种非平凡路径。
 *
 * <p>方案:维护 ThreadLocal 栈,记录当前触发事件路径上的 recordId。
 * 再次触发同一 recordId 时即识别嵌套,阻断并告警。
 *
 * <p><strong>线程模型注意</strong>:Spring {@code @Async} 默认 ThreadPoolTaskExecutor,
 * 不同 worker 线程各自独立栈;同一执行链上事件落在同线程才能命中。
 * 但这是<strong>运行时最后一根防线</strong> —— 真正应该靠
 * {@link WorkflowGraphValidator} 静态校验 + {@link TriggerRateLimiter} 频率限制
 * 把环消掉。
 *
 * <p>栈深度限制(默认 5)防止跨多 workflow 的递归链。
 */
public final class NestedTriggerGuard {

    /** 嵌套触发深度上限 — 超过此值视为异常递归,阻断。 */
    public static final int MAX_NESTED_DEPTH = 5;

    private static final ThreadLocal<java.util.Deque<String>> STACK =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    private NestedTriggerGuard() {}

    /**
     * 检查是否允许触发。
     *
     * @return true=允许,false=被嵌套阻断
     */
    public static boolean tryPush(String recordId) {
        if (recordId == null) return true;
        java.util.Deque<String> stack = STACK.get();

        // 同 recordId 已在栈中 → 嵌套触发,阻断
        if (stack.contains(recordId)) {
            return false;
        }
        // 栈太深 → 异常递归,阻断
        if (stack.size() >= MAX_NESTED_DEPTH) {
            return false;
        }
        stack.push(recordId);
        return true;
    }

    /** 弹出栈(try-finally 中调用,保证退出触发逻辑后清栈)。 */
    public static void pop() {
        java.util.Deque<String> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        // 栈空时主动 remove,避免 ThreadLocal 内存泄漏
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    /** 测试 / 监控用:当前栈深度。 */
    public static int currentDepth() {
        return STACK.get().size();
    }

    /** 测试用:清空 ThreadLocal。 */
    public static void clear() {
        STACK.remove();
    }
}
