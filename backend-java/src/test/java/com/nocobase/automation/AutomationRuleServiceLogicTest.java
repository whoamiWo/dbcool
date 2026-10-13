package com.nocobase.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.automation.entity.AutomationExecutionEntity;
import com.nocobase.automation.entity.AutomationRuleEntity;
import com.nocobase.automation.repository.AutomationExecutionRepository;
import com.nocobase.automation.repository.AutomationRuleRepository;
import com.nocobase.meta.CollectionService;
import com.nocobase.notification.NotificationService;
import com.nocobase.notification.WebhookDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PHASE103 T1: AutomationRuleService 核心逻辑覆盖（条件评估 / 动作执行 / 执行历史）。
 *
 * <p>补齐 PHASE95 遗留的覆盖率缺口（该类 missed=182 行，是全仓 Service 层最大缺口）。
 * 只测**被测类自身的逻辑**，外部依赖（repository / notification / collection）全部 mock。
 */
class AutomationRuleServiceLogicTest {

    private AutomationRuleRepository ruleRepository;
    private AutomationExecutionRepository executionRepository;
    private CollectionService collectionService;
    private NotificationService notificationService;
    private AutomationRuleService service;

    private static final String TENANT = "tenant_default";

    @BeforeEach
    void setUp() {
        ruleRepository = mock(AutomationRuleRepository.class);
        executionRepository = mock(AutomationExecutionRepository.class);
        collectionService = mock(CollectionService.class);
        notificationService = mock(NotificationService.class);
        service = new AutomationRuleService(
                ruleRepository, executionRepository, collectionService,
                notificationService, mock(WebhookDispatcher.class), new ObjectMapper());
    }

    private AutomationRuleEntity ruleWith(List<Map<String, Object>> conditions, List<Map<String, Object>> actions) {
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setId(UUID.randomUUID());
        rule.setName("r");
        rule.setCollectionName("c");
        rule.setTriggerType("RECORD_CREATE");
        rule.setConditions(conditions);
        rule.setActions(actions);
        rule.setEnabled(true);
        rule.setTenantId(TENANT);
        rule.setExecutionCount(0);
        return rule;
    }

    // ==================== 条件评估 ====================

    @Nested
    @DisplayName("条件评估（evaluateConditions）")
    class Conditions {

        @Test
        @DisplayName("无条件 → 直接通过")
        void noConditions_passes() {
            AutomationRuleEntity rule = ruleWith(null, List.of(Map.of("type", "NOTIFY", "message", "hi")));
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            service.executeRule(rule.getId(), Map.of("recordId", "r1"), null, TENANT);

            verify(executionRepository).save(any());
        }

        @Test
        @DisplayName("eq 命中 → 通过")
        void eq_matched() {
            AutomationRuleEntity rule = ruleWith(
                    List.of(Map.of("field", "status", "operator", "eq", "value", "active")),
                    List.of(Map.of("type", "NOTIFY", "message", "hi")));
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            service.executeRule(rule.getId(), Map.of("status", "active"), null, TENANT);

            verify(executionRepository).save(any());
        }

        @Test
        @DisplayName("eq 未命中 → 不执行动作、不落执行记录")
        void eq_notMatched_skips() {
            AutomationRuleEntity rule = ruleWith(
                    List.of(Map.of("field", "status", "operator", "eq", "value", "active")),
                    List.of(Map.of("type", "NOTIFY", "message", "hi")));
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            service.executeRule(rule.getId(), Map.of("status", "closed"), null, TENANT);

            verify(executionRepository, never()).save(any());
            verify(notificationService, never()).fire(any(), any(), any(), any());
        }

        @Test
        @DisplayName("字段缺失（actualValue == null）→ 不通过")
        void missingField_fails() {
            AutomationRuleEntity rule = ruleWith(
                    List.of(Map.of("field", "amount", "operator", "gt", "value", 100)),
                    List.of(Map.of("type", "NOTIFY", "message", "hi")));
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            service.executeRule(rule.getId(), Map.of("other", "x"), null, TENANT);

            verify(executionRepository, never()).save(any());
        }

        @Test
        @DisplayName("neq / contains / gt / lt / gte / lte / in 各分支")
        void allOperators() {
            // 通过 executeRule 的副作用（executionRepository.save 被调用）判断条件是否命中
            assertThat(eval("neq", "a", "b")).isTrue();
            assertThat(eval("neq", "a", "a")).isFalse();
            assertThat(eval("contains", "hello world", "world")).isTrue();
            assertThat(eval("contains", "hello", "zzz")).isFalse();
            assertThat(eval("gt", 10, 5)).isTrue();
            assertThat(eval("gt", 5, 10)).isFalse();
            assertThat(eval("lt", 5, 10)).isTrue();
            assertThat(eval("lt", 10, 5)).isFalse();
            assertThat(eval("gte", 10, 10)).isTrue();
            assertThat(eval("gte", 9, 10)).isFalse();
            assertThat(eval("lte", 10, 10)).isTrue();
            assertThat(eval("lte", 11, 10)).isFalse();
            assertThat(eval("in", "a", List.of("a", "b"))).isTrue();
            assertThat(eval("in", "c", List.of("a", "b"))).isFalse();
            assertThat(eval("bogus", "a", "a")).isFalse();
        }

        /** 返回 true 表示条件命中（执行记录被保存）。每次调用前重置 mock。 */
        private boolean eval(String op, Object actual, Object expected) {
            org.mockito.Mockito.reset(executionRepository);
            AutomationRuleEntity rule = ruleWith(
                    List.of(Map.of("field", "f", "operator", op, "value", expected)),
                    List.of(Map.of("type", "NOTIFY", "message", "hi")));
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));
            service.executeRule(rule.getId(), Map.of("f", actual), null, TENANT);
            var captor = org.mockito.ArgumentCaptor.forClass(AutomationExecutionEntity.class);
            verify(executionRepository, org.mockito.Mockito.atMostOnce()).save(captor.capture());
            return captor.getAllValues().size() == 1;
        }
    }

    // ==================== 动作执行 ====================

    @Nested
    @DisplayName("动作执行（executeActions）")
    class Actions {

        @Test
        @DisplayName("NOTIFY → notificationService.fire 被调用")
        void notify_fires() {
            AutomationRuleEntity rule = ruleWith(
                    List.of(),
                    List.of(Map.of("type", "NOTIFY", "message", "hello", "recipient", "u1")));
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            service.executeRule(rule.getId(), Map.of("recordId", "r1"), null, TENANT);

            verify(notificationService).fire(eq(TENANT), eq("automation.notify"), eq("u1"), any());
        }

        @Test
        @DisplayName("UPDATE_RECORD → collectionService.updateRecord 被调用")
        void updateRecord_called() {
            AutomationRuleEntity rule = ruleWith(
                    List.of(),
                    List.of(Map.of("type", "UPDATE_RECORD",
                            "updates", Map.of("status", "done"))));
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            service.executeRule(rule.getId(), Map.of("recordId", "r1"), null, TENANT);

            verify(collectionService).updateRecord(eq("c"), eq("r1"), eq(Map.of("status", "done")), eq(TENANT));
        }

        @Test
        @DisplayName("CREATE_RECORD → collectionService.insertRecord 被调用")
        void createRecord_called() {
            AutomationRuleEntity rule = ruleWith(
                    List.of(),
                    List.of(Map.of("type", "CREATE_RECORD",
                            "collection", "other", "fields", Map.of("k", "v"))));
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            service.executeRule(rule.getId(), Map.of("recordId", "r1"), null, TENANT);

            verify(collectionService).insertRecord(eq("other"), eq(Map.of("k", "v")), eq(TENANT));
        }

        @Test
        @DisplayName("未知动作类型 → 执行状态 FAILED 且记录错误信息")
        void unknownAction_marksFailed() {
            AutomationRuleEntity rule = ruleWith(List.of(), List.of(Map.of("type", "BOGUS")));
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            service.executeRule(rule.getId(), Map.of("recordId", "r1"), null, TENANT);

            var captor = org.mockito.ArgumentCaptor.forClass(AutomationExecutionEntity.class);
            verify(executionRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
            assertThat(captor.getValue().getErrorMessage()).contains("未知动作类型");
        }

        @Test
        @DisplayName("CREATE_RECORD 内部异常被局部吞掉（设计行为），执行仍 SUCCESS")
        void actionInternalException_swallowedLocally() {
            // executeCreateRecord 内部 try-catch：单动作失败不中断整条规则
            when(collectionService.insertRecord(any(), any(), any()))
                    .thenThrow(new RuntimeException("db down"));
            AutomationRuleEntity rule = ruleWith(
                    List.of(),
                    List.of(Map.of("type", "CREATE_RECORD",
                            "collection", "other", "fields", Map.of("k", "v"))));
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            service.executeRule(rule.getId(), Map.of("recordId", "r1"), null, TENANT);

            var captor = org.mockito.ArgumentCaptor.forClass(AutomationExecutionEntity.class);
            verify(executionRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("空动作列表 → 不落执行记录")
        void emptyActions_noExecution() {
            AutomationRuleEntity rule = ruleWith(List.of(), List.of());
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            service.executeRule(rule.getId(), Map.of("recordId", "r1"), null, TENANT);

            verify(executionRepository, never()).save(any());
        }

        @Test
        @DisplayName("规则已禁用 → 抛异常")
        void disabledRule_throws() {
            AutomationRuleEntity rule = ruleWith(List.of(), List.of(Map.of("type", "NOTIFY")));
            rule.setEnabled(false);
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            assertThatThrownBy(() ->
                    service.executeRule(rule.getId(), Map.of(), null, TENANT))
                    .hasMessageContaining("规则已禁用");
        }

        @Test
        @DisplayName("执行成功后统计字段自增")
        void executionCount_increments() {
            AutomationRuleEntity rule = ruleWith(
                    List.of(), List.of(Map.of("type", "NOTIFY", "message", "hi")));
            when(ruleRepository.findByIdAndTenantId(any(), eq(TENANT))).thenReturn(Optional.of(rule));

            service.executeRule(rule.getId(), Map.of("recordId", "r1"), null, TENANT);

            assertThat(rule.getExecutionCount()).isEqualTo(1);
            assertThat(rule.getLastExecutionAt()).isNotNull();
            verify(ruleRepository).save(rule);
        }
    }

    // ==================== CRUD / 历史 ====================

    @Nested
    @DisplayName("CRUD 与执行历史")
    class CrudAndHistory {

        @Test
        @DisplayName("createRule 保存并返回实体")
        void createRule_saves() {
            service.createRule("n", "d", "c", "RECORD_CREATE",
                    Map.of(), List.of(), List.of(), TENANT, null);
            verify(ruleRepository).save(any());
        }

        @Test
        @DisplayName("updateRule 只更新非 null 字段")
        void updateRule_partial() {
            UUID id = UUID.randomUUID();
            AutomationRuleEntity rule = new AutomationRuleEntity();
            rule.setId(id);
            rule.setName("old");
            rule.setDescription("old-desc");
            when(ruleRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(rule));

            service.updateRule(id, "new", null, null, null, TENANT);

            assertThat(rule.getName()).isEqualTo("new");
            assertThat(rule.getDescription()).isEqualTo("old-desc"); // 未传 → 保持
            verify(ruleRepository).save(rule);
        }

        @Test
        @DisplayName("toggleRule 切换启用状态")
        void toggleRule() {
            UUID id = UUID.randomUUID();
            AutomationRuleEntity rule = new AutomationRuleEntity();
            rule.setId(id);
            rule.setEnabled(true);
            when(ruleRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(rule));

            service.toggleRule(id, false, TENANT);

            assertThat(rule.getEnabled()).isFalse();
        }

        @Test
        @DisplayName("deleteRule 删除")
        void deleteRule() {
            UUID id = UUID.randomUUID();
            AutomationRuleEntity rule = new AutomationRuleEntity();
            rule.setId(id);
            when(ruleRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(rule));

            service.deleteRule(id, TENANT);

            verify(ruleRepository).delete(rule);
        }

        @Test
        @DisplayName("listRules 按租户查询")
        void listRules() {
            service.listRules(TENANT);
            verify(ruleRepository).findByTenantIdOrderByUpdatedAtDesc(TENANT);
        }

        @Test
        @DisplayName("listExecutions 先做租户归属校验再查执行记录")
        void listExecutions_checksOwnership() {
            UUID id = UUID.randomUUID();
            AutomationRuleEntity rule = new AutomationRuleEntity();
            rule.setId(id);
            when(ruleRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(rule));

            service.listExecutions(id, TENANT);

            verify(executionRepository).findByRuleIdOrderByCreatedAtDesc(id);
        }

        @Test
        @DisplayName("listExecutions 跨租户 → 抛 404")
        void listExecutions_wrongTenant_404() {
            UUID id = UUID.randomUUID();
            when(ruleRepository.findByIdAndTenantId(id, "other")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listExecutions(id, "other"))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }
}
