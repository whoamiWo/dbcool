package com.nocobase.automation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.automation.entity.AutomationRuleEntity;
import com.nocobase.automation.repository.AutomationRuleRepository;
import com.nocobase.event.RecordChangeEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE105: AutomationTriggerListener 测试。
 *
 * <p>私有方法 matchesTrigger / evaluateConditions / evaluateCondition / toDouble
 * 均通过 onRecordChange 间接覆盖：构造不同 triggerType + 条件的规则与事件来触发各分支。
 */
class AutomationTriggerListenerTest {

    private AutomationRuleRepository ruleRepository;
    private AutomationRuleService automationRuleService;
    private AutomationTriggerListener listener;

    private static final String COLL = "orders";
    private static final String TENANT = "t1";

    @BeforeEach
    void setUp() {
        ruleRepository = mock(AutomationRuleRepository.class);
        automationRuleService = mock(AutomationRuleService.class);
        listener = new AutomationTriggerListener(ruleRepository, automationRuleService);
    }

    private AutomationRuleEntity rule(String triggerType, List<Map<String, Object>> conditions) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setId(UUID.randomUUID());
        r.setCollectionName(COLL);
        r.setTriggerType(triggerType);
        r.setConditions(conditions);
        return r;
    }

    private RecordChangeEvent event(RecordChangeEvent.ChangeType type, Map<String, Object> data) {
        return new RecordChangeEvent(type, COLL, "rec-1", data, TENANT, UUID.randomUUID());
    }

    private void stubRules(AutomationRuleEntity... rules) {
        when(ruleRepository.findByCollectionNameAndTenantIdAndEnabledTrue(COLL, TENANT))
                .thenReturn(List.of(rules));
    }

    // ── 入参短路 ────────────────────────────────────────────
    @Test
    void onRecordChange_nullEvent_returnsEarly() {
        listener.onRecordChange(null);
        verify(ruleRepository, never()).findByCollectionNameAndTenantIdAndEnabledTrue(any(), any());
    }

    @Test
    void onRecordChange_nullCollection_returnsEarly() {
        listener.onRecordChange(
                new RecordChangeEvent(RecordChangeEvent.ChangeType.CREATE, null, "r", Map.of(), TENANT, UUID.randomUUID()));
        verify(ruleRepository, never()).findByCollectionNameAndTenantIdAndEnabledTrue(any(), any());
    }

    // ── matchesTrigger：三类 × (RECORD_* / ALL) ──────────────
    @Test
    void create_matchesRecordCreate_executes() {
        stubRules(rule("RECORD_CREATE", null));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of("amount", 100)));

        verify(automationRuleService).executeRule(any(), any(), eq("rec-1"), any(), eq(TENANT));
    }

    @Test
    void update_matchesRecordUpdate_executes() {
        stubRules(rule("RECORD_UPDATE", null));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.UPDATE, Map.of("amount", 5)));

        verify(automationRuleService).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void delete_matchesRecordDelete_executes() {
        stubRules(rule("RECORD_DELETE", null));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.DELETE, Map.of()));

        verify(automationRuleService).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void allTriggerType_matchesAnyChangeType() {
        stubRules(rule("ALL", null));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of()));
        listener.onRecordChange(event(RecordChangeEvent.ChangeType.UPDATE, Map.of()));
        listener.onRecordChange(event(RecordChangeEvent.ChangeType.DELETE, Map.of()));

        verify(automationRuleService, org.mockito.Mockito.times(3))
                .executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void triggerTypeMismatch_skips() {
        // 事件是 CREATE，但规则只匹配 UPDATE → 不执行
        stubRules(rule("RECORD_UPDATE", null));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of()));

        verify(automationRuleService, never()).executeRule(any(), any(), any(), any(), any());
    }

    // ── evaluateConditions ──────────────────────────────────
    @Test
    void noConditions_treatedAsMatch() {
        stubRules(rule("ALL", List.of())); // 空条件

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of()));

        verify(automationRuleService).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void conditionEquals_matches() {
        stubRules(rule("ALL", List.of(Map.of("field", "status", "operator", "equals", "value", "paid"))));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of("status", "paid")));

        verify(automationRuleService).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void conditionEquals_notMatch_skips() {
        stubRules(rule("ALL", List.of(Map.of("field", "status", "operator", "equals", "value", "paid"))));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of("status", "unpaid")));

        verify(automationRuleService, never()).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void conditionNotEquals() {
        stubRules(rule("ALL", List.of(Map.of("field", "status", "operator", "not_equals", "value", "draft"))));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of("status", "paid")));

        verify(automationRuleService).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void conditionContains() {
        stubRules(rule("ALL", List.of(Map.of("field", "title", "operator", "contains", "value", "urgent"))));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of("title", "[urgent] 订单")));

        verify(automationRuleService).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void conditionGreaterThan_number() {
        stubRules(rule("ALL", List.of(Map.of("field", "amount", "operator", "greater_than", "value", 50))));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of("amount", 100)));

        verify(automationRuleService).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void conditionGreaterThan_numericString_toDoubleParses() {
        // actual 是字符串 "100"，toDouble 走 parse 分支
        stubRules(rule("ALL", List.of(Map.of("field", "amount", "operator", "greater_than", "value", "50"))));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of("amount", "100")));

        verify(automationRuleService).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void conditionGreaterThan_unparseable_toDoubleZero() {
        // 不可解析字符串 → toDouble 返回 0，0 > 50 为 false → 跳过
        stubRules(rule("ALL", List.of(Map.of("field", "amount", "operator", "greater_than", "value", 50))));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of("amount", "abc")));

        verify(automationRuleService, never()).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void conditionLessThan() {
        stubRules(rule("ALL", List.of(Map.of("field", "amount", "operator", "less_than", "value", 50))));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of("amount", 10)));

        verify(automationRuleService).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void conditionUnknownOperator_defaultTrue() {
        stubRules(rule("ALL", List.of(Map.of("field", "x", "operator", "unknown_op", "value", "y"))));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of("x", "z")));

        verify(automationRuleService).executeRule(any(), any(), any(), any(), any());
    }

    @Test
    void conditionFieldMissing_actualNull_skips() {
        // data 中没有该字段 → actual == null → evaluateCondition 返回 false
        stubRules(rule("ALL", List.of(Map.of("field", "missing", "operator", "equals", "value", "x"))));

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of("other", 1)));

        verify(automationRuleService, never()).executeRule(any(), any(), any(), any(), any());
    }

    // ── 异常不阻断（单条规则失败不影响循环） ────────────────
    @Test
    void executeThrows_caughtAndContinues() {
        AutomationRuleEntity r1 = rule("ALL", null);
        AutomationRuleEntity r2 = rule("ALL", null);
        when(ruleRepository.findByCollectionNameAndTenantIdAndEnabledTrue(COLL, TENANT))
                .thenReturn(List.of(r1, r2));
        // executeRule 返回 void，必须用 doThrow().when()
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(automationRuleService).executeRule(any(), any(), any(), any(), any());

        // 不应向外抛出
        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of()));
    }

    @Test
    void noMatchingRules_noExecution() {
        when(ruleRepository.findByCollectionNameAndTenantIdAndEnabledTrue(COLL, TENANT))
                .thenReturn(List.of());

        listener.onRecordChange(event(RecordChangeEvent.ChangeType.CREATE, Map.of()));

        verify(automationRuleService, never()).executeRule(any(), any(), any(), any(), any());
    }
}
