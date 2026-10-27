package com.nocobase.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 数据质量规则(B7)。
 *
 * <p>四类规则:
 * <ul>
 *   <li>{@code NOT_NULL} — 字段非空</li>
 *   <li>{@code UNIQUE}   — 字段值唯一</li>
 *   <li>{@code REGEX}    — 字段值匹配正则({@code ruleConfig.pattern})</li>
 *   <li>{@code RANGE}    — 数值落在区间({@code ruleConfig.min/max/field})</li>
 * </ul>
 */
@Entity
@Table(name = "data_quality_rule")
public class DataQualityRuleEntity {

    /** 规则类型。 */
    public enum RuleType { NOT_NULL, UNIQUE, REGEX, RANGE }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "collection_name", nullable = false, length = 64)
    private String collectionName;

    @Column(name = "field_name", length = 128)
    private String fieldName;

    @Column(name = "rule_type", nullable = false, length = 32)
    private String ruleType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_config", columnDefinition = "jsonb")
    private String ruleConfig;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity = "WARN";

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public DataQualityRuleEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }

    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }

    public String getRuleConfig() { return ruleConfig; }
    public void setRuleConfig(String ruleConfig) { this.ruleConfig = ruleConfig; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
