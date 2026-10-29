package com.nocobase.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.integration.datasource.ExternalDataSourceEntity;
import com.nocobase.integration.datasource.ExternalDataSourceRepository;
import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.meta.CollectionRepository;
import com.nocobase.tenant.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DataGovernanceService} 单元测试（PHASE 100 覆盖率补测）。
 *
 * <p>覆盖保留策略 CRUD/执行（DELETE 与 ARCHIVE 两条分支）、质量规则 CRUD、
 * 调度器到期判断、以及血缘查询的四种形态（未知 / 原生 / 外部有源 / 外部源缺失）。
 * 租户上下文通过 {@link TenantContext#set} 注入，验证跨租户拒绝逻辑。
 */
class DataGovernanceServiceTest {

    private RetentionPolicyRepository policyRepo;
    private DataQualityRuleRepository ruleRepo;
    private CollectionRepository collectionMetaRepo;
    private ExternalDataSourceRepository externalDataSourceRepo;
    private DataGovernanceService service;

    @BeforeEach
    void setUp() {
        policyRepo = mock(RetentionPolicyRepository.class);
        ruleRepo = mock(DataQualityRuleRepository.class);
        collectionMetaRepo = mock(CollectionRepository.class);
        externalDataSourceRepo = mock(ExternalDataSourceRepository.class);
        service = new DataGovernanceService(policyRepo, ruleRepo, collectionMetaRepo, externalDataSourceRepo);
        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RetentionPolicyEntity policy(UUID id, String tenant, String action, Integer days) {
        RetentionPolicyEntity p = new RetentionPolicyEntity();
        p.setId(id);
        p.setTenantId(tenant);
        p.setCollectionName("users");
        p.setAction(action);
        p.setRetentionDays(days == null ? 365 : days);
        return p;
    }

    private CollectionMetaEntity meta(String name, UUID datasourceId) {
        CollectionMetaEntity cm = new CollectionMetaEntity();
        cm.setId(UUID.randomUUID());
        cm.setName(name);
        cm.setDatasourceId(datasourceId);
        cm.setExternalTableName(datasourceId != null ? "users_src" : null);
        return cm;
    }

    // ================= 保留策略 CRUD =================

    @Test
    @DisplayName("createPolicy：collectionName 空 → 拒绝")
    void createPolicy_blankName_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.createPolicy("  ", null, null, true));
    }

    @Test
    @DisplayName("createPolicy：action/retentionDays 缺省 → ARCHIVE + 365")
    void createPolicy_defaults() {
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RetentionPolicyEntity p = service.createPolicy("users", null, null, true);
        assertEquals("ARCHIVE", p.getAction());
        assertEquals(365, p.getRetentionDays());
        assertEquals("t1", p.getTenantId());
    }

    @Test
    @DisplayName("createPolicy：retentionDays<=0 → 回退 365；action 大写归一")
    void createPolicy_normalizes() {
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RetentionPolicyEntity p = service.createPolicy("users", "delete", -5, true);
        assertEquals("DELETE", p.getAction());
        assertEquals(365, p.getRetentionDays());
    }

    @Test
    @DisplayName("updatePolicy：不存在 → IllegalArgumentException")
    void updatePolicy_notFound() {
        UUID id = UUID.randomUUID();
        when(policyRepo.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.updatePolicy(id, "DELETE", 30, true));
    }

    @Test
    @DisplayName("updatePolicy：跨租户 → SecurityException（防越权）")
    void updatePolicy_crossTenant_rejected() {
        UUID id = UUID.randomUUID();
        when(policyRepo.findById(id)).thenReturn(Optional.of(policy(id, "t-other", "ARCHIVE", 365)));
        assertThrows(SecurityException.class, () -> service.updatePolicy(id, "DELETE", 30, true));
    }

    @Test
    @DisplayName("updatePolicy：同租户 → 仅更新非 null 字段，days<=0 被忽略")
    void updatePolicy_success() {
        UUID id = UUID.randomUUID();
        RetentionPolicyEntity p = policy(id, "t1", "ARCHIVE", 365);
        when(policyRepo.findById(id)).thenReturn(Optional.of(p));
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RetentionPolicyEntity updated = service.updatePolicy(id, "delete", 0, false);
        assertEquals("DELETE", updated.getAction());
        assertEquals(365, updated.getRetentionDays());   // 0 被忽略
        assertFalse(updated.getEnabled());
        assertNotNull(updated.getUpdatedAt());
    }

    @Test
    @DisplayName("deletePolicy / listPolicies：透传仓储")
    void deleteAndListPolicy() {
        UUID id = UUID.randomUUID();
        when(policyRepo.findByTenantIdOrderByCreatedAtDesc("t1")).thenReturn(List.of());
        assertEquals(0, service.listPolicies().size());
        service.deletePolicy(id);
        verify(policyRepo).deleteById(id);
    }

    // ================= 保留策略执行 =================

    @Test
    @DisplayName("executePolicy：不存在 → IllegalArgumentException")
    void executePolicy_notFound() {
        UUID id = UUID.randomUUID();
        when(policyRepo.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.executePolicy(id));
    }

    @Test
    @DisplayName("executePolicy：DELETE 且元数据存在 → 删除 metadata")
    void executePolicy_delete_removesMeta() {
        UUID id = UUID.randomUUID();
        RetentionPolicyEntity p = policy(id, "t1", "DELETE", 365);
        when(policyRepo.findById(id)).thenReturn(Optional.of(p));
        CollectionMetaEntity cm = meta("users", null);
        when(collectionMetaRepo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.of(cm));

        Map<String, Object> res = service.executePolicy(id);

        verify(collectionMetaRepo).delete(cm);
        assertEquals("DELETE", res.get("action"));
        assertEquals("users", res.get("collection"));
        assertNotNull(p.getLastExecutedAt());   // 回写执行时间
        verify(policyRepo).save(p);
    }

    @Test
    @DisplayName("executePolicy：DELETE 但元数据不存在 → 跳过（不抛）")
    void executePolicy_delete_metaMissing_skips() {
        UUID id = UUID.randomUUID();
        when(policyRepo.findById(id)).thenReturn(Optional.of(policy(id, "t1", "DELETE", 365)));
        when(collectionMetaRepo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.empty());

        Map<String, Object> res = service.executePolicy(id);
        assertEquals("DELETE", res.get("action"));
        verify(collectionMetaRepo, never()).delete(any());
    }

    @Test
    @DisplayName("executePolicy：ARCHIVE → 置 collection 只读")
    void executePolicy_archive_freezesCollection() {
        UUID id = UUID.randomUUID();
        when(policyRepo.findById(id)).thenReturn(Optional.of(policy(id, "t1", "ARCHIVE", 365)));
        CollectionMetaEntity cm = meta("users", null);
        when(collectionMetaRepo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.of(cm));

        service.executePolicy(id);

        assertTrue(cm.getReadOnly());
        verify(collectionMetaRepo).save(cm);
    }

    @Test
    @DisplayName("executePolicy：ARCHIVE 但元数据不存在 → 无操作（不抛）")
    void executePolicy_archive_metaMissing_noop() {
        UUID id = UUID.randomUUID();
        when(policyRepo.findById(id)).thenReturn(Optional.of(policy(id, "t1", "ARCHIVE", 365)));
        when(collectionMetaRepo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.empty());

        Map<String, Object> res = service.executePolicy(id);
        assertEquals("ARCHIVE", res.get("action"));
        verify(collectionMetaRepo, never()).save(any());
    }

    // ================= 调度器 =================

    @Test
    @DisplayName("executeAllDuePolicies：未到期跳过，已到期执行")
    void executeAllDuePolicies_filtersByDueDate() {
        UUID dueId = UUID.randomUUID();
        UUID freshId = UUID.randomUUID();

        RetentionPolicyEntity due = policy(dueId, "t1", "ARCHIVE", 1);
        due.setLastExecutedAt(Instant.now().minus(10, ChronoUnit.DAYS));   // 早已到期
        RetentionPolicyEntity fresh = policy(freshId, "t1", "ARCHIVE", 365);
        fresh.setLastExecutedAt(Instant.now());                            // 刚执行，未到期

        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(due, fresh));
        when(policyRepo.findById(dueId)).thenReturn(Optional.of(due));
        when(collectionMetaRepo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.empty());

        int executed = service.executeAllDuePolicies();

        assertEquals(1, executed);
        verify(policyRepo).findById(dueId);
    }

    @Test
    @DisplayName("executeAllDuePolicies：lastExecutedAt 为空 → 视为立即到期")
    void executeAllDuePolicies_neverExecuted_isDue() {
        UUID id = UUID.randomUUID();
        RetentionPolicyEntity p = policy(id, "t1", "ARCHIVE", 365);
        p.setLastExecutedAt(null);
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(p));
        when(policyRepo.findById(id)).thenReturn(Optional.of(p));
        when(collectionMetaRepo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.empty());

        assertEquals(1, service.executeAllDuePolicies());
    }

    @Test
    @DisplayName("executeAllDuePolicies：单条失败不影响其余（异常被吞并计数）")
    void executeAllDuePolicies_toleratesFailure() {
        UUID badId = UUID.randomUUID();
        UUID goodId = UUID.randomUUID();
        RetentionPolicyEntity bad = policy(badId, "t1", "ARCHIVE", 1);
        RetentionPolicyEntity good = policy(goodId, "t1", "ARCHIVE", 1);

        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(bad, good));
        when(policyRepo.findById(badId)).thenReturn(Optional.empty());   // 触发 IAE
        when(policyRepo.findById(goodId)).thenReturn(Optional.of(good));
        when(collectionMetaRepo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.empty());

        assertEquals(1, service.executeAllDuePolicies());
    }

    // ================= 质量规则 =================

    @Test
    @DisplayName("createRule：collectionName / ruleType 空 → 拒绝")
    void createRule_blankRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createRule("  ", "f", "NOT_NULL", null, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> service.createRule("users", "f", " ", null, null, true));
    }

    @Test
    @DisplayName("createRule：severity 缺省 WARN，ruleType 大写，config 转字符串")
    void createRule_defaultsAndNormalization() {
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DataQualityRuleEntity r = service.createRule("users", "email", "regex", Map.of("pattern", "^a"), null, true);
        assertEquals("REGEX", r.getRuleType());
        assertEquals("WARN", r.getSeverity());
        assertNotNull(r.getRuleConfig());
        assertEquals("t1", r.getTenantId());
    }

    @Test
    @DisplayName("createRule：ruleConfig 为 null → 存 null")
    void createRule_nullConfig() {
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DataQualityRuleEntity r = service.createRule("users", "email", "NOT_NULL", null, "ERROR", true);
        assertEquals("ERROR", r.getSeverity());
    }

    @Test
    @DisplayName("updateRule：不存在 → IAE；跨租户 → SecurityException")
    void updateRule_errors() {
        UUID id = UUID.randomUUID();
        when(ruleRepo.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.updateRule(id, "cfg", null, true));

        DataQualityRuleEntity other = new DataQualityRuleEntity();
        other.setId(id);
        other.setTenantId("t-other");
        when(ruleRepo.findById(id)).thenReturn(Optional.of(other));
        assertThrows(SecurityException.class, () -> service.updateRule(id, "cfg", null, true));
    }

    @Test
    @DisplayName("updateRule：同租户 → 更新字段，空 severity 被忽略")
    void updateRule_success() {
        UUID id = UUID.randomUUID();
        DataQualityRuleEntity r = new DataQualityRuleEntity();
        r.setId(id);
        r.setTenantId("t1");
        r.setSeverity("WARN");
        when(ruleRepo.findById(id)).thenReturn(Optional.of(r));
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DataQualityRuleEntity updated = service.updateRule(id, "cfg", "  ", false);
        assertEquals("WARN", updated.getSeverity());   // 空白 severity 被忽略
        assertFalse(updated.getEnabled());
    }

    @Test
    @DisplayName("deleteRule / listRules / listRulesByCollection：透传仓储")
    void ruleQueries() {
        UUID id = UUID.randomUUID();
        when(ruleRepo.findByTenantIdOrderByCreatedAtDesc("t1")).thenReturn(List.of());
        assertEquals(0, service.listRules().size());
        when(ruleRepo.findByTenantIdAndCollectionNameAndEnabledTrue("t1", "users")).thenReturn(List.of());
        assertEquals(0, service.listRulesByCollection("users").size());
        service.deleteRule(id);
        verify(ruleRepo).deleteById(id);
    }

    // ================= 血缘 =================

    @Test
    @DisplayName("lineage：Collection 不存在 → source=unknown, external=false")
    void lineage_unknown() {
        when(collectionMetaRepo.findByNameAndTenantId("nope", "t1")).thenReturn(Optional.empty());
        Map<String, Object> res = service.lineage("nope");
        assertEquals("unknown", res.get("source"));
        assertEquals(false, res.get("external"));
    }

    @Test
    @DisplayName("lineage：原生 Collection（无 datasourceId）→ source=native")
    void lineage_native() {
        CollectionMetaEntity cm = meta("users", null);
        cm.setReadOnly(true);
        when(collectionMetaRepo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.of(cm));

        Map<String, Object> res = service.lineage("users");
        assertEquals("native", res.get("source"));
        assertEquals(false, res.get("external"));
        assertEquals(true, res.get("readOnly"));
    }

    @Test
    @DisplayName("lineage：外部映射且能查到数据源 → 带 vendor/name")
    void lineage_externalWithDatasource() {
        UUID dsId = UUID.randomUUID();
        CollectionMetaEntity cm = meta("users", dsId);
        when(collectionMetaRepo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.of(cm));

        ExternalDataSourceEntity ds = new ExternalDataSourceEntity();
        ds.setId(dsId);
        ds.setVendor("postgresql");
        ds.setName("pg-main");
        when(externalDataSourceRepo.findById(dsId)).thenReturn(Optional.of(ds));

        Map<String, Object> res = service.lineage("users");
        assertEquals("external", res.get("source"));
        assertEquals(true, res.get("external"));
        assertEquals(dsId.toString(), res.get("datasourceId"));
        assertEquals("users_src", res.get("externalTableName"));
        assertEquals("postgresql", res.get("datasourceVendor"));
        assertEquals("pg-main", res.get("datasourceName"));
    }

    @Test
    @DisplayName("lineage：外部映射但数据源已删 → 不带 vendor/name")
    void lineage_externalDatasourceMissing() {
        UUID dsId = UUID.randomUUID();
        when(collectionMetaRepo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.of(meta("users", dsId)));
        when(externalDataSourceRepo.findById(dsId)).thenReturn(Optional.empty());

        Map<String, Object> res = service.lineage("users");
        assertEquals("external", res.get("source"));
        assertEquals(true, res.get("external"));
        assertFalse(res.containsKey("datasourceVendor"));
        assertFalse(res.containsKey("datasourceName"));
    }

    @Test
    @DisplayName("lineage：readOnly 为 null → 归一为 false")
    void lineage_readOnlyNull() {
        CollectionMetaEntity cm = meta("users", null);
        cm.setReadOnly(null);
        when(collectionMetaRepo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.of(cm));
        assertEquals(false, service.lineage("users").get("readOnly"));
    }
}
