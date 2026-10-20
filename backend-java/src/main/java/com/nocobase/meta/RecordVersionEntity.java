package com.nocobase.meta;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 业务记录版本实体 — 记录 Collection 动态表(data_&lt;collection&gt;)中每条记录的变更快照。
 *
 * <p>PHASE125:与 Wiki({@code wiki_version})的语义一致 —— 每次 INSERT / UPDATE /
 * DELETE / RESTORE 写入一条版本,支持版本列表、字段级差异对比与一键回滚。
 *
 * <p><b>为什么 data_json 用 TEXT 而不是 JSONB</b>:
 * <ul>
 *   <li>版本快照仅用于读取与展示,不需要 JSONB 的查询/索引能力</li>
 *   <li>H2 测试环境(见 {@code application-test.properties})的 H2Dialect 不识别
 *       jsonb 类型(V54 迁移注释已记录 jsonb → TEXT 的处理),用 TEXT 可让
 *       {@code ddl-auto=create-drop} 在测试中正确建表</li>
 * </ul>
 *
 * <p><b>多租户</b>:带 {@code tenant_id},查询时必须显式带租户条件(动态表本身按
 * schema 隔离,而本表是跨租户共享的元数据表)。
 */
@Entity
@Table(name = "record_version")
public class RecordVersionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "collection_name", nullable = false, length = 128)
    private String collectionName;

    @Column(name = "record_id", nullable = false)
    private UUID recordId;

    @Column(name = "version", nullable = false)
    private Integer version;

    /** 该版本的完整记录快照(JSON 字符串)。 */
    @Column(name = "data_json", nullable = false, columnDefinition = "TEXT")
    private String dataJson;

    /** 字段级变更差异 JSON:{@code [{field, oldValue, newValue}]}。 */
    @Column(name = "changed_fields", columnDefinition = "TEXT")
    private String changedFields;

    /** 操作类型:INSERT / UPDATE / DELETE / RESTORE。 */
    @Column(name = "operation", length = 16)
    private String operation;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    public RecordVersionEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

    public UUID getRecordId() { return recordId; }
    public void setRecordId(UUID recordId) { this.recordId = recordId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getDataJson() { return dataJson; }
    public void setDataJson(String dataJson) { this.dataJson = dataJson; }

    public String getChangedFields() { return changedFields; }
    public void setChangedFields(String changedFields) { this.changedFields = changedFields; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public UUID getChangedBy() { return changedBy; }
    public void setChangedBy(UUID changedBy) { this.changedBy = changedBy; }

    public Instant getChangedAt() { return changedAt; }
    public void setChangedAt(Instant changedAt) { this.changedAt = changedAt; }
}
