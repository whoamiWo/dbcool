package com.nocobase.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NestedTriggerGuardTest {

    @AfterEach
    void cleanup() {
        NestedTriggerGuard.clear();
    }

    @Test
    void nullRecordId_alwaysAllowed() {
        assertThat(NestedTriggerGuard.tryPush(null)).isTrue();
        NestedTriggerGuard.pop();
    }

    @Test
    void singlePushPop_balances() {
        assertThat(NestedTriggerGuard.tryPush("rec-1")).isTrue();
        assertThat(NestedTriggerGuard.currentDepth()).isEqualTo(1);
        NestedTriggerGuard.pop();
        assertThat(NestedTriggerGuard.currentDepth()).isEqualTo(0);
    }

    @Test
    void duplicateRecordId_blocked() {
        assertThat(NestedTriggerGuard.tryPush("rec-1")).isTrue();
        // 同一 recordId 二次触发 → 阻断
        assertThat(NestedTriggerGuard.tryPush("rec-1")).isFalse();
        NestedTriggerGuard.pop();
    }

    @Test
    void differentRecordIds_sequentialAllowed() {
        assertThat(NestedTriggerGuard.tryPush("rec-A")).isTrue();
        assertThat(NestedTriggerGuard.tryPush("rec-B")).isTrue();
        assertThat(NestedTriggerGuard.currentDepth()).isEqualTo(2);
        NestedTriggerGuard.pop();
        NestedTriggerGuard.pop();
        assertThat(NestedTriggerGuard.currentDepth()).isEqualTo(0);
    }

    @Test
    void maxDepthExceeded_blocked() {
        // 推满 MAX_NESTED_DEPTH 层
        for (int i = 0; i < NestedTriggerGuard.MAX_NESTED_DEPTH; i++) {
            assertThat(NestedTriggerGuard.tryPush("rec-" + i))
                    .as("push #%d should be allowed", i)
                    .isTrue();
        }
        // 第 MAX_NESTED_DEPTH + 1 次 → 阻断
        assertThat(NestedTriggerGuard.tryPush("rec-overflow")).isFalse();
        // 清理栈
        for (int i = 0; i < NestedTriggerGuard.MAX_NESTED_DEPTH; i++) {
            NestedTriggerGuard.pop();
        }
    }

    @Test
    void popOnEmptyStack_safe() {
        NestedTriggerGuard.pop();
        NestedTriggerGuard.pop();
        assertThat(NestedTriggerGuard.currentDepth()).isEqualTo(0);
    }

    @Test
    void clearAfterEmpty_removesThreadLocal() {
        NestedTriggerGuard.tryPush("rec-1");
        NestedTriggerGuard.pop();
        // pop 后栈空 → ThreadLocal 已 remove
        NestedTriggerGuard.clear();
        assertThat(NestedTriggerGuard.currentDepth()).isEqualTo(0);
    }
}
