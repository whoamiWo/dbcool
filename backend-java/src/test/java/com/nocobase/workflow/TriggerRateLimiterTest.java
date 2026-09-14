package com.nocobase.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TriggerRateLimiter 单元测试(Week 41 D4a.3 — 死循环防护).
 */
class TriggerRateLimiterTest {

    private TriggerRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TriggerRateLimiter();
    }

    @Test
    void first5_triggersAllowed() {
        String wfId = UUID.randomUUID().toString();
        String recId = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allowTrigger(wfId, recId))
                    .as("第 %d 次触发应允许", i + 1)
                    .isTrue();
        }
    }

    @Test
    void sixthTrigger_blocked() {
        String wfId = UUID.randomUUID().toString();
        String recId = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) limiter.allowTrigger(wfId, recId);
        // 第 6 次阻断
        assertThat(limiter.allowTrigger(wfId, recId)).isFalse();
    }

    @Test
    void differentRecordIds_independentCounters() {
        String wfId = UUID.randomUUID().toString();
        String recId1 = "record-1";
        String recId2 = "record-2";
        for (int i = 0; i < 5; i++) limiter.allowTrigger(wfId, recId1);
        // record1 已满,record2 仍可触发
        assertThat(limiter.allowTrigger(wfId, recId2)).isTrue();
    }

    @Test
    void differentWorkflows_independentCounters() {
        String wfId1 = UUID.randomUUID().toString();
        String wfId2 = UUID.randomUUID().toString();
        String recId = "shared-record";
        for (int i = 0; i < 5; i++) limiter.allowTrigger(wfId1, recId);
        // wf1 已满,wf2 仍可触发(同 record 不同 workflow)
        assertThat(limiter.allowTrigger(wfId2, recId)).isTrue();
    }

    @Test
    void nullWorkflowId_alwaysAllowed() {
        assertThat(limiter.allowTrigger(null, "rec")).isTrue();
        assertThat(limiter.allowTrigger(null, "rec")).isTrue();
    }

    @Test
    void nullRecordId_alwaysAllowed() {
        assertThat(limiter.allowTrigger("wf", null)).isTrue();
    }

    @Test
    void reset_clearsAllCounters() {
        String wfId = UUID.randomUUID().toString();
        String recId = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) limiter.allowTrigger(wfId, recId);
        assertThat(limiter.allowTrigger(wfId, recId)).isFalse();
        limiter.reset();
        assertThat(limiter.allowTrigger(wfId, recId)).isTrue();
    }

    @Test
    void countFor_returnsAccurateCount() {
        String wfId = UUID.randomUUID().toString();
        String recId = UUID.randomUUID().toString();
        assertThat(limiter.countFor(wfId, recId)).isEqualTo(0);
        limiter.allowTrigger(wfId, recId);
        limiter.allowTrigger(wfId, recId);
        assertThat(limiter.countFor(wfId, recId)).isEqualTo(2);
    }
}
