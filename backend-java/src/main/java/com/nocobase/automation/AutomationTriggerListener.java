package com.nocobase.automation;

import com.nocobase.automation.entity.AutomationRuleEntity;
import com.nocobase.automation.repository.AutomationRuleRepository;
import com.nocobase.event.RecordChangeEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

/**
 * 自动化触发器监听器 — 订阅 RecordChangeEvent，按 rule.triggerType 匹配并执行动作。
 *
 * <p>参考 {@link WorkflowTriggerListener} 的订阅模式，
 * 当 Collection 记录发生 CREATE/UPDATE/DELETE 时，自动匹配并执行对应自动化规则。
 */
@Component
public class AutomationTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(AutomationTriggerListener.class);

    private final AutomationRuleRepository ruleRepository;
    private final AutomationRuleService automationRuleService;

    public AutomationTriggerListener(AutomationRuleRepository ruleRepository,
                                     AutomationRuleService automationRuleService) {
        this.ruleRepository = ruleRepository;
        this.automationRuleService = automationRuleService;
    }

    /**
     * 订阅 RecordChangeEvent，异步执行匹配的自动化规则。
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecordChange(RecordChangeEvent event) {
        if (event == null || event.getCollectionName() == null) {
            return;
        }
        String collectionName = event.getCollectionName();
        var changeType = event.getChangeType();
        var tenantId = event.getTenantId();
        var userId = event.getUserId();

        // 查找匹配的自动化规则
        List<AutomationRuleEntity> rules = ruleRepository
                .findByCollectionNameAndTenantIdAndEnabledTrue(collectionName, tenantId);

        for (AutomationRuleEntity rule : rules) {
            try {
                if (!matchesTrigger(rule, changeType)) {
                    continue;
                }
                if (!evaluateConditions(rule, event.getData())) {
                    continue;
                }
                // 执行动作
                automationRuleService.executeRule(rule, event.getData(), event.getRecordId(), userId);
                log.info("[AUTOMATION TRIGGER] ruleId={} triggered by {} on {}",
                        rule.getId(), changeType, collectionName);
            } catch (Exception e) {
                log.warn("[AUTOMATION TRIGGER] ruleId={} failed: {}", rule.getId(), e.getMessage());
            }
        }
    }

    /**
     * 判断规则的 triggerType 是否匹配当前事件类型。
     */
    private boolean matchesTrigger(AutomationRuleEntity rule, RecordChangeEvent.ChangeType changeType) {
        String triggerType = rule.getTriggerType();
        return switch (changeType) {
            case CREATE -> "RECORD_CREATE".equals(triggerType) || "ALL".equals(triggerType);
            case UPDATE -> "RECORD_UPDATE".equals(triggerType) || "ALL".equals(triggerType);
            case DELETE -> "RECORD_DELETE".equals(triggerType) || "ALL".equals(triggerType);
        };
    }

    /**
     * 简单条件求值（复用 AutomationRuleService 中的条件逻辑）。
     */
    private boolean evaluateConditions(AutomationRuleEntity rule, Map<String, Object> data) {
        List<Map<String, Object>> conditions = rule.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        // 简化：所有条件需全部满足（AND 逻辑）
        for (Map<String, Object> cond : conditions) {
            String field = (String) cond.get("field");
            String operator = (String) cond.get("operator");
            Object expected = cond.get("value");
            Object actual = data.get(field);
            if (!evaluateCondition(field, operator, expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateCondition(String field, String operator, Object expected, Object actual) {
        if (actual == null) return false;
        return switch (operator) {
            case "equals" -> actual.equals(expected);
            case "not_equals" -> !actual.equals(expected);
            case "contains" -> actual.toString().contains(expected.toString());
            case "greater_than" -> {
                double a = toDouble(actual);
                double b = toDouble(expected);
                yield a > b;
            }
            case "less_than" -> {
                double a = toDouble(actual);
                double b = toDouble(expected);
                yield a < b;
            }
            default -> true;
        };
    }

    private double toDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
    }
}
