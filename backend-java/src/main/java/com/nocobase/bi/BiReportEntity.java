package com.nocobase.bi;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * BI 报表实体 — 数据透视 + 图表配置。
 *
 * <p>type: PIVOT / CHART / DASHBOARD
 * <p>config 示例:
 * <ul>
 *   <li>PIVOT: {"rows":["department"],"columns":["status"],"values":[{"field":"amount","agg":"SUM"}]}</li>
 *   <li>CHART: {"chartType":"bar","xField":"month","yField":"amount","series":"status"}</li>
 *   <li>DASHBOARD: {"panels":[{"reportId":"...","layout":{"x":0,"y":0,"w":12,"h":6}}]}</li>
 * </ul>
 */
@Entity
@Table(name = "bi_reports")
public class BiReportEntity {

    public enum Type { PIVOT, CHART, DASHBOARD }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "collection_name", nullable = false, length = 64)
    private String collectionName;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "title", length = 128)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private Type type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb", nullable = false)
    private String configJson = "{}";

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public BiReportEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
