package com.nocobase.automation;

import com.nocobase.automation.entity.AutomationExecutionEntity;
import com.nocobase.automation.entity.AutomationRuleEntity;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动化规则 REST API。
 */
@RestController
@Tag(name = "Automation Rules", description = "自动化规则引擎")
@RequestMapping("/api/automation")
public class AutomationRuleController {

    private final AutomationRuleService automationService;

    public AutomationRuleController(AutomationRuleService automationService) {
        this.automationService = automationService;
    }

    /** 创建规则 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        AutomationRuleEntity rule = automationService.createRule(
                (String) body.get("name"),
                (String) body.get("description"),
                (String) body.get("collectionName"),
                (String) body.get("triggerType"),
                (Map<String, Object>) body.get("triggerConfig"),
                (List<Map<String, Object>>) body.get("conditions"),
                (List<Map<String, Object>>) body.get("actions"),
                user.tenantId(),
                user.userId()
        );
        
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0,
                "message", "success",
                "data", rule
        ));
    }

    /** 更新规则 */
    @PutMapping("/{ruleId}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable UUID ruleId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        AutomationRuleEntity rule = automationService.updateRule(
                ruleId,
                (String) body.get("name"),
                (String) body.get("description"),
                (List<Map<String, Object>>) body.get("conditions"),
                (List<Map<String, Object>>) body.get("actions"),
                user.tenantId()
        );
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", rule
        ));
    }

    /** 启用/禁用规则 */
    @PostMapping("/{ruleId}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(
            @PathVariable UUID ruleId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        AutomationRuleEntity rule = automationService.toggleRule(ruleId, enabled, user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("enabled", rule.getEnabled())
        ));
    }

    /** 删除规则 */
    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable UUID ruleId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        automationService.deleteRule(ruleId, user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success"
        ));
    }

    /** 列出规则 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @AuthenticationPrincipal AuthenticatedUser user) {
        List<AutomationRuleEntity> rules = automationService.listRules(user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("rules", rules)
        ));
    }

    /** 获取规则详情 */
    @GetMapping("/{ruleId}")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable UUID ruleId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        AutomationRuleEntity rule = automationService.getRule(ruleId);
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", rule
        ));
    }

    /** 手动触发执行 */
    @PostMapping("/{ruleId}/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @PathVariable UUID ruleId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        Map<String, Object> triggerData = (Map<String, Object>) body.get("triggerData");
        var execution = automationService.executeRule(ruleId, triggerData, user.userId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", execution
        ));
    }

    /** 获取执行历史 */
    @GetMapping("/{ruleId}/executions")
    public ResponseEntity<Map<String, Object>> listExecutions(
            @PathVariable UUID ruleId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        List<AutomationExecutionEntity> executions = automationService.listExecutions(ruleId, user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("executions", executions)
        ));
    }
}
