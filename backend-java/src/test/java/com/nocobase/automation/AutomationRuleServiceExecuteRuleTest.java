package com.nocobase.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.automation.entity.AutomationExecutionEntity;
import com.nocobase.automation.entity.AutomationRuleEntity;
import com.nocobase.automation.repository.AutomationExecutionRepository;
import com.nocobase.automation.repository.AutomationRuleRepository;
import com.nocobase.meta.CollectionService;
import com.nocobase.notification.NotificationService;
import com.nocobase.notification.WebhookDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PHASE105: 覆盖 AutomationRuleService.executeRule(entity,...) 执行链路
 * (NOTIFY / UPDATE_RECORD / CREATE_RECORD / WEBHOOK / 未知动作 分发 + 异常捕获)。
 *
 * <p>executeRule(entity) 在 AutomationTriggerListener 被调用，与 executeRule(UUID) 重载
 * 不同——它直接接收已解析的 rule 对象、循环执行 actions，并把每次结果落 execution。
 */
class AutomationRuleServiceExecuteRuleTest {

    private AutomationRuleRepository ruleRepository;
    private AutomationExecutionRepository executionRepository;
    private CollectionService collectionService;
    private NotificationService notificationService;
    private WebhookDispatcher webhookDispatcher;
    private AutomationRuleService service;

    private static final String TENANT = "tenant_default";

    @BeforeEach
    void setUp() {
        ruleRepository = mock(AutomationRuleRepository.class);
        executionRepository = mock(AutomationExecutionRepository.class);
        collectionService = mock(CollectionService.class);
        notificationService = mock(NotificationService.class);
        webhookDispatcher = mock(WebhookDispatcher.class);
        service = new AutomationRuleService(
                ruleRepository, executionRepository, collectionService,
                notificationService, webhookDispatcher, new ObjectMapper());
    }

    private AutomationRuleEntity ruleOf(String collection, List<Map<String, Object>> actions) {
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setId(UUID.randomUUID());
        rule.setName("demo-rule");
        rule.setCollectionName(collection);
        rule.setTriggerType("RECORD_CREATE");
        rule.setConditions(List.of());
        rule.setActions(actions);
        rule.setEnabled(true);
        rule.setExecutionCount(0);
        rule.setTenantId(TENANT);
        return rule;
    }

    @Test
    void executeRule_notifyAction_firesNotification() {
        AutomationRuleEntity rule = ruleOf("orders", List.of(
                Map.of("type", "NOTIFY", "message", "新订单", "recipient", "ops")));
        UUID userId = UUID.randomUUID();

        service.executeRule(rule, Map.of("recordId", "r1"), "r1", userId, TENANT);

        verify(notificationService).fire(eq(TENANT), eq("automation.notify"), eq("ops"), any(Map.class));
        ArgumentCaptor<AutomationExecutionEntity> cap =
                ArgumentCaptor.forClass(AutomationExecutionEntity.class);
        verify(executionRepository, atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues()).isNotEmpty();
    }

    @Test
    void executeRule_notifyActionNoRecipient_firesAll() {
        AutomationRuleEntity rule = ruleOf("orders", List.of(
                Map.of("type", "NOTIFY", "message", "hi")));

        service.executeRule(rule, Map.of("recordId", "r1"), "r1", UUID.randomUUID(), TENANT);

        verify(notificationService).fire(eq(TENANT), eq("automation.notify"), eq("all"), any(Map.class));
    }

    @Test
    void executeRule_updateRecordAction_delegatesToCollectionService() {
        AutomationRuleEntity rule = ruleOf("orders", List.of(
                Map.of("type", "UPDATE_RECORD",
                        "updates", Map.of("status", "paid"))));
        when(collectionService.updateRecord(anyString(), anyString(), any(Map.class), anyString()))
                .thenReturn(true);

        service.executeRule(rule, Map.of("recordId", "r1", "status", "new"), "r1",
                UUID.randomUUID(), TENANT);

        verify(collectionService).updateRecord(eq("orders"), eq("r1"),
                eq(Map.of("status", "paid")), eq(TENANT));
    }

    @Test
    void executeRule_updateRecordActionMissingFields_skips() {
        // 无 recordId → 不委托
        AutomationRuleEntity rule = ruleOf("orders", List.of(
                Map.of("type", "UPDATE_RECORD", "updates", Map.of("a", 1))));

        service.executeRule(rule, Map.of(), "r1", UUID.randomUUID(), TENANT);

        verify(collectionService, org.mockito.Mockito.never()).updateRecord(
                anyString(), anyString(), any(Map.class), anyString());
    }

    @Test
    void executeRule_createRecordAction_delegatesInsert() {
        AutomationRuleEntity rule = ruleOf("orders", List.of(
                Map.of("type", "CREATE_RECORD",
                        "collection", "shipments",
                        "fields", Map.of("orderId", "r1"))));
        UUID newId = UUID.randomUUID();
        when(collectionService.insertRecord(eq("shipments"), any(Map.class), eq(TENANT)))
                .thenReturn(newId);

        service.executeRule(rule, Map.of(), "r1", UUID.randomUUID(), TENANT);

        verify(collectionService).insertRecord(eq("shipments"), eq(Map.of("orderId", "r1")), eq(TENANT));
    }

    @Test
    void executeRule_webhookAction_withBlankUrl_isNoop() {
        AutomationRuleEntity rule = ruleOf("orders", List.of(
                Map.of("type", "WEBHOOK", "url", "  ")));

        service.executeRule(rule, Map.of(), "r1", UUID.randomUUID(), TENANT);

        // url 空白 → 不触 HTTP，execution 仍 SUCCESS
        ArgumentCaptor<AutomationExecutionEntity> cap =
                ArgumentCaptor.forClass(AutomationExecutionEntity.class);
        verify(executionRepository, atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void executeRule_unknownActionType_markedFailed() {
        AutomationRuleEntity rule = ruleOf("orders", List.of(
                Map.of("type", "BOGUS_TYPE", "x", "y")));

        service.executeRule(rule, Map.of(), "r1", UUID.randomUUID(), TENANT);

        ArgumentCaptor<AutomationExecutionEntity> cap =
                ArgumentCaptor.forClass(AutomationExecutionEntity.class);
        verify(executionRepository, atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(cap.getAllValues().get(1).getErrorMessage())
                .contains("未知动作类型");
    }

    @Test
    void executeRule_notifyThrows_isCaughtAndMarkedFailed() {
        AutomationRuleEntity rule = ruleOf("orders", List.of(
                Map.of("type", "NOTIFY", "message", "boom")));
        when(notificationService.fire(anyString(), anyString(), anyString(), any(Map.class)))
                .thenThrow(new RuntimeException("notify down"));

        service.executeRule(rule, Map.of(), "r1", UUID.randomUUID(), TENANT);

        // executeNotify 自身 catch 了 fire 异常 → 不阻断, execution 仍 SUCCESS
        ArgumentCaptor<AutomationExecutionEntity> cap =
                ArgumentCaptor.forClass(AutomationExecutionEntity.class);
        verify(executionRepository, atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void executeRule_nullActions_successNoop() {
        AutomationRuleEntity rule = ruleOf("orders", null);

        service.executeRule(rule, Map.of(), "r1", UUID.randomUUID(), TENANT);

        ArgumentCaptor<AutomationExecutionEntity> cap =
                ArgumentCaptor.forClass(AutomationExecutionEntity.class);
        verify(executionRepository, atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getStatus()).isEqualTo("SUCCESS");
    }

}
