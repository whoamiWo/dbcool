package com.nocobase.integration.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * G1/G3：组织同步离职安全策略（飞书与钉钉共用）。
 */
class OrgSyncSafetyTest {

    @Test
    @DisplayName("完整性：部门全成功、零失败、零错误才算完整")
    void completeRequiresAllDepartmentsAndZeroFailures() {
        assertThat(OrgSyncSafety.isComplete(0, 0, 3, 3)).isTrue();
        assertThat(OrgSyncSafety.isComplete(1, 0, 3, 3)).isFalse();
        assertThat(OrgSyncSafety.isComplete(0, 1, 3, 3)).isFalse();
        assertThat(OrgSyncSafety.isComplete(0, 0, 2, 3)).isFalse();
    }

    @Test
    @DisplayName("G3：比例阈值下限为 1（4 见 1 离允许，不会误熔断小组织）")
    void ratioThresholdHasFloorOfOne() {
        assertThat(OrgSyncSafety.resignRatioThreshold(4)).isEqualTo(1);
        assertThat(OrgSyncSafety.resignRatioThreshold(1)).isEqualTo(1);
        assertThat(OrgSyncSafety.resignRatioThreshold(120)).isEqualTo(24);
    }

    @Test
    @DisplayName("G3：4 见 1 离 → ALLOW")
    void smallOrgSingleResignationAllowed() {
        assertThat(OrgSyncSafety.evaluate(1, 4, false, false))
                .isEqualTo(OrgSyncSafety.Decision.ALLOW);
    }

    @Test
    @DisplayName("G3：11 人离职 → 超过硬上限熔断")
    void bulkResignationTrips() {
        assertThat(OrgSyncSafety.evaluate(11, 120, false, false))
                .isEqualTo(OrgSyncSafety.Decision.TRIPPED);
    }

    @Test
    @DisplayName("G3：force=true → 跳过熔断")
    void forceSkipsBreaker() {
        assertThat(OrgSyncSafety.evaluate(11, 120, false, true))
                .isEqualTo(OrgSyncSafety.Decision.FORCED);
    }

    @Test
    @DisplayName("dryRun 优先于熔断：只返回名单，不判拒绝")
    void dryRunWinsOverBreaker() {
        assertThat(OrgSyncSafety.evaluate(11, 120, true, false))
                .isEqualTo(OrgSyncSafety.Decision.DRY_RUN);
    }

    @Test
    @DisplayName("没有待离职用户 → NONE；一个用户都没看到 → 熔断")
    void edgeCases() {
        assertThat(OrgSyncSafety.evaluate(0, 120, false, false))
                .isEqualTo(OrgSyncSafety.Decision.NONE);
        assertThat(OrgSyncSafety.evaluate(1, 0, false, false))
                .isEqualTo(OrgSyncSafety.Decision.TRIPPED);
    }

    @Test
    @DisplayName("熔断文案包含待离职与已见人数")
    void breakerReasonMentionsCounts() {
        assertThat(OrgSyncSafety.breakerReason(11, 120))
                .contains("11").contains("120");
    }
}
