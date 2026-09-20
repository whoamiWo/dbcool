package com.nocobase.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.automation.entity.AutomationExecutionEntity;
import com.nocobase.automation.entity.AutomationRuleEntity;
import com.nocobase.automation.repository.AutomationExecutionRepository;
import com.nocobase.automation.repository.AutomationRuleRepository;
import com.nocobase.meta.CollectionService;
import com.nocobase.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.UUID;

/**
 * 自动化规则引擎 — 参考 Zapier / Make 设计。
 *
 * <p>核心流程:
 * <ol>
 *   <li>触发器(Trigger) 检测事件</li>
 *   <li>条件(Condition) 过滤</li>
 *   <li>动作(Action) 执行</li>
 * </ol>
 *
 * <p>触发器类型:
 * <ul>
 *   <li>RECORD_CREATE - 新记录创建</li>
 *   <li>RECORD_UPDATE - 记录更新</li>
 *   <li>RECORD_DELETE - 记录删除</li>
 *   <li>SCHEDULED - 定时触发</li>
 * </ul>
 *
 * <p>动作类型:
 * <ul>
 *   <li>NOTIFY - 发送通知</li>
 *   <li>UPDATE_RECORD - 更新记录</li>
 *   <li>CREATE_RECORD - 创建记录</li>
 *   <li>WEBHOOK - 调用外部 API</li>
 *   <li>SEND_EMAIL - 发送邮件</li>
 * </ul>
 */
@Service
public class AutomationRuleService {

    private static final Logger log = LoggerFactory.getLogger(AutomationRuleService.class);

    private final AutomationRuleRepository ruleRepository;
    private final AutomationExecutionRepository executionRepository;
    private final CollectionService collectionService;
    private final ObjectMapper objectMapper;

    public AutomationRuleService(
            AutomationRuleRepository ruleRepository,
            AutomationExecutionRepository executionRepository,
            CollectionService collectionService,
            ObjectMapper objectMapper
    ) {
        this.ruleRepository = ruleRepository;
        this.executionRepository = executionRepository;
        this.collectionService = collectionService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建自动化规则。
     */
    @Transactional
    public AutomationRuleEntity createRule(
            String name, String description, String collectionName,
            String triggerType, Map<String, Object> triggerConfig,
            List<Map<String, Object>> conditions,
            List<Map<String, Object>> actions,
            String tenantId, UUID createdBy) {
        
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setId(UUID.randomUUID());
        rule.setName(name);
        rule.setDescription(description);
        rule.setCollectionName(collectionName);
        rule.setTriggerType(triggerType);
        rule.setTriggerConfig(triggerConfig);
        rule.setConditions(conditions);
        rule.setActions(actions);
        rule.setEnabled(true);
        rule.setTenantId(tenantId);
        rule.setCreatedBy(createdBy);
        rule.setCreatedAt(Instant.now());
        rule.setUpdatedAt(Instant.now());
        
        return ruleRepository.save(rule);
    }

    /**
     * 更新自动化规则。
     */
    @Transactional
    public AutomationRuleEntity updateRule(UUID ruleId, String name, String description,
            List<Map<String, Object>> conditions, List<Map<String, Object>> actions) {
        AutomationRuleEntity rule = getRule(ruleId);
        
        if (name != null) rule.setName(name);
        if (description != null) rule.setDescription(description);
        if (conditions != null) rule.setConditions(conditions);
        if (actions != null) rule.setActions(actions);
        rule.setUpdatedAt(Instant.now());
        
        return ruleRepository.save(rule);
    }

    /**
     * 启用/禁用规则。
     */
    @Transactional
    public AutomationRuleEntity toggleRule(UUID ruleId, boolean enabled) {
        AutomationRuleEntity rule = getRule(ruleId);
        rule.setEnabled(enabled);
        rule.setUpdatedAt(Instant.now());
        return ruleRepository.save(rule);
    }

    /**
     * 删除规则。
     */
    @Transactional
    public void deleteRule(UUID ruleId) {
        ruleRepository.deleteById(ruleId);
    }

    /**
     * 获取规则详情。
     */
    public AutomationRuleEntity getRule(UUID ruleId) {
        return ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuntimeException("规则不存在: " + ruleId));
    }

    /**
     * 列出租户的所有规则。
     */
    public List<AutomationRuleEntity> listRules(String tenantId) {
        return ruleRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    /**
     * 手动触发规则执行。
     */
    @Transactional
    public AutomationExecutionEntity executeRule(UUID ruleId, Map<String, Object> triggerData, UUID triggeredBy) {
        AutomationRuleEntity rule = getRule(ruleId);
        
        if (!rule.getEnabled()) {
            throw new RuntimeException("规则已禁用");
        }
        
        // 评估条件
        if (!evaluateConditions(rule, triggerData)) {
            log.info("automation rule {} conditions not met, skipping", ruleId);
            return null;
        }
        
        // 执行动作
        AutomationExecutionEntity execution = executeActions(rule, triggerData);
        
        // 更新统计
        rule.setExecutionCount(rule.getExecutionCount() + 1);
        rule.setLastExecutionAt(Instant.now());
        ruleRepository.save(rule);
        
        return execution;
    }

    /**
     * 评估条件列表 — ALL 条件必须满足。
     */
    private boolean evaluateConditions(AutomationRuleEntity rule, Map<String, Object> data) {
        List<Map<String, Object>> conditions = rule.getConditions();
        if (conditions == null || conditions.isEmpty()) return true;
        
        for (Map<String, Object> condition : conditions) {
            if (!evaluateCondition(condition, data)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 评估单个条件。
     */
    private boolean evaluateCondition(Map<String, Object> condition, Map<String, Object> data) {
        String field = (String) condition.get("field");
        String operator = (String) condition.getOrDefault("operator", "eq");
        Object expectedValue = condition.get("value");
        Object actualValue = data.get(field);
        
        if (actualValue == null) return false;
        
        return switch (operator) {
            case "eq" -> String.valueOf(actualValue).equals(String.valueOf(expectedValue));
            case "neq" -> !String.valueOf(actualValue).equals(String.valueOf(expectedValue));
            case "contains" -> String.valueOf(actualValue).contains(String.valueOf(expectedValue));
            case "gt" -> toDouble(actualValue) > toDouble(expectedValue);
            case "lt" -> toDouble(actualValue) < toDouble(expectedValue);
            case "gte" -> toDouble(actualValue) >= toDouble(expectedValue);
            case "lte" -> toDouble(actualValue) <= toDouble(expectedValue);
            case "in" -> {
                List<?> list = (List<?>) expectedValue;
                yield list != null && list.contains(actualValue);
            }
            default -> false;
        };
    }

    /**
     * 执行动作列表。
     */
    private AutomationExecutionEntity executeActions(AutomationRuleEntity rule, Map<String, Object> data) {
        List<Map<String, Object>> actions = rule.getActions();
        if (actions == null || actions.isEmpty()) return null;
        
        AutomationExecutionEntity execution = new AutomationExecutionEntity();
        execution.setId(UUID.randomUUID());
        execution.setRuleId(rule.getId());
        execution.setRecordId((String) data.get("recordId"));
        execution.setStatus("SUCCESS");
        execution.setTriggeredBy(null);
        execution.setCreatedAt(Instant.now());
        
        StringBuilder errorMsg = new StringBuilder();
        long startTime = System.currentTimeMillis();
        
        for (Map<String, Object> action : actions) {
            String actionType = (String) action.get("type");
            try {
                executeAction(action, data, rule);
            } catch (Exception e) {
                errorMsg.append(actionType).append(": ").append(e.getMessage()).append("; ");
                execution.setStatus("FAILED");
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        execution.setExecutionTimeMs(duration);
        if (errorMsg.length() > 0) {
            execution.setErrorMessage(errorMsg.toString());
        }
        
        executionRepository.save(execution);
        return execution;
    }

    /**
     * 执行单个动作。
     */
    private void executeAction(Map<String, Object> action, Map<String, Object> data, AutomationRuleEntity rule) {
        String actionType = (String) action.get("type");
        
        switch (actionType) {
            case "NOTIFY" -> executeNotify(action, data);
            case "UPDATE_RECORD" -> executeUpdateRecord(action, data, rule);
            case "CREATE_RECORD" -> executeCreateRecord(action, data, rule);
            case "WEBHOOK" -> executeWebhook(action, data);
            default -> throw new IllegalArgumentException("未知动作类型: " + actionType);
        }
    }

    private void executeNotify(Map<String, Object> action, Map<String, Object> data) {
        String message = (String) action.get("message");
        log.info("[AUTOMATION NOTIFY] {}", message);
        // TODO: 集成通知服务
    }

    private void executeUpdateRecord(Map<String, Object> action, Map<String, Object> data, AutomationRuleEntity rule) {
        String recordId = (String) data.get("recordId");
        Map<String, Object> updates = (Map<String, Object>) action.get("updates");
        if (recordId != null && updates != null) {
            // 更新集合记录
            log.info("[AUTOMATION UPDATE_RECORD] recordId={}, updates={}", recordId, updates);
        }
    }

    private void executeCreateRecord(Map<String, Object> action, Map<String, Object> data, AutomationRuleEntity rule) {
        String collectionName = (String) action.get("collection");
        Map<String, Object> fields = (Map<String, Object>) action.get("fields");
        if (collectionName != null && fields != null) {
            log.info("[AUTOMATION CREATE_RECORD] collection={}, fields={}", collectionName, fields);
        }
    }

    private void executeWebhook(Map<String, Object> action, Map<String, Object> data) {
        String url = (String) action.get("url");
        log.info("[AUTOMATION WEBHOOK] url={}", url);
        // TODO: 调用外部 Webhook
    }

    private double toDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
    }

    /**
     * 获取规则执行历史。
     */
    public List<AutomationExecutionEntity> listExecutions(UUID ruleId, String tenantId) {
        return executionRepository.findByRuleIdOrderByCreatedAtDesc(ruleId);
    }
}
