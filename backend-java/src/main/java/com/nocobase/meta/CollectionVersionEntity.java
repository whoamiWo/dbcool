package com.nocobase.meta;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 集合元数据版本记录 (Wave 4 C2)。
 *
 * <p>每次 Collection 更新时自动生成新版本，保留 fieldsJson 快照，支持回滚和审计。
 */
@Entity
@Table(name = "collection_version")
public class CollectionVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 关联的 Collection ID。 */
    @Column(name = "collection_id", nullable = false)
    private UUID collectionId;

    /** 版本号 (自增)。 */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 字段定义快照 (JSON)。 */
    @Column(name = "fields_json", columnDefinition = "jsonb", nullable = false)
    private String fieldsJson;

    /** 变更描述。 */
    @Column(name = "change_desc", length = 500)
    private String changeDesc;

    /** 租户 ID。 */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 操作人 ID。 */
    @Column(name = "created_by")
    private UUID createdBy;

    public CollectionVersionEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getCollectionId() { return collectionId; }
    public void setCollectionId(UUID collectionId) { this.collectionId = collectionId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getFieldsJson() { return fieldsJson; }
    public void setFieldsJson(String fieldsJson) { this.fieldsJson = fieldsJson; }

    public String getChangeDesc() { return changeDesc; }
    public void setChangeDesc(String changeDesc) { this.changeDesc = changeDesc; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
