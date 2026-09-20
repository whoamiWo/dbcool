package com.nocobase.automation.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动化规则实体。
 */
@Entity
@Table(name = "automation_rule")
public class AutomationRuleEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "collection_name", nullable = false, length = 128)
    private String collectionName;

    @Column(name = "trigger_type", nullable = false, length = 50)
    private String triggerType;

    @Column(name = "trigger_config", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String triggerConfigJson = "{}";

    @Column(name = "conditions_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String conditionsJson = "[]";

    @Column(name = "actions_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String actionsJson = "[]";

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "execution_count")
    private Integer executionCount = 0;

    @Column(name = "last_execution_at")
    private Instant lastExecutionAt;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public String getTriggerConfigJson() { return triggerConfigJson; }
    public void setTriggerConfigJson(String triggerConfigJson) { this.triggerConfigJson = triggerConfigJson; }

    public Map<String, Object> getTriggerConfig() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(triggerConfigJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new java.util.HashMap<>();
        }
    }
    public void setTriggerConfig(Map<String, Object> config) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            this.triggerConfigJson = mapper.writeValueAsString(config);
        } catch (Exception e) {
            this.triggerConfigJson = "{}";
        }
    }

    public String getConditionsJson() { return conditionsJson; }
    public void setConditionsJson(String conditionsJson) { this.conditionsJson = conditionsJson; }

    public List<Map<String, Object>> getConditions() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(conditionsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    public void setConditions(List<Map<String, Object>> conditions) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            this.conditionsJson = mapper.writeValueAsString(conditions);
        } catch (Exception e) {
            this.conditionsJson = "[]";
        }
    }

    public String getActionsJson() { return actionsJson; }
    public void setActionsJson(String actionsJson) { this.actionsJson = actionsJson; }

    public List<Map<String, Object>> getActions() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(actionsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    public void setActions(List<Map<String, Object>> actions) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            this.actionsJson = mapper.writeValueAsString(actions);
        } catch (Exception e) {
            this.actionsJson = "[]";
        }
    }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Integer getExecutionCount() { return executionCount; }
    public void setExecutionCount(Integer executionCount) { this.executionCount = executionCount; }

    public Instant getLastExecutionAt() { return lastExecutionAt; }
    public void setLastExecutionAt(Instant lastExecutionAt) { this.lastExecutionAt = lastExecutionAt; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
