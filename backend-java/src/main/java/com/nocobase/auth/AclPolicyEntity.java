package com.nocobase.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 权限策略(US-303/304/305 统一表).
 *
 * <p>type + action 决定 config 结构:
 * <ul>
 *   <li>type=field,  config={"hidden": ["ssn"], "readonly": ["salary"]}</li>
 *   <li>type=row,    config={"filters": [{"field": "dept", "op": "eq", "value": "..."}]}</li>
 *   <li>type=action, action="delete", config={} (仅表示允许/禁止)</li>
 * </ul>
 */
@Entity
@Table(name = "acl_policies")
public class AclPolicyEntity {

    public enum Type {
        FIELD, ROW, ACTION
    }

    public enum Action {
        CREATE, READ, UPDATE, DELETE
    }

    /**
     * 数据范围类型 (Wave3 B7)。
     *
     * <ul>
     *   <li>{@link #ALL}    —— 全部数据(管理员)</li>
     *   <li>{@link #DEPT}   —— 本部门及下级部门</li>
     *   <li>{@link #SELF}   —— 仅本人创建的数据</li>
     *   <li>{@link #CUSTOM} —— 自定义规则(见 dataScopeConfig)</li>
     * </ul>
     */
    public enum DataScopeType {
        ALL, DEPT, SELF, CUSTOM
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private Type type;

    @Column(name = "subject", nullable = false, length = 64)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 16)
    private Action action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb", nullable = false)
    private String configJson = "{}";

    @Enumerated(EnumType.STRING)
    @Column(name = "data_scope_type", length = 16)
    private DataScopeType dataScopeType = DataScopeType.ALL;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_scope_config", columnDefinition = "jsonb")
    private String dataScopeConfigJson;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AclPolicyEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getRoleId() { return roleId; }
    public void setRoleId(UUID roleId) { this.roleId = roleId; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public DataScopeType getDataScopeType() { return dataScopeType; }
    public void setDataScopeType(DataScopeType dataScopeType) { this.dataScopeType = dataScopeType; }

    public String getDataScopeConfigJson() { return dataScopeConfigJson; }
    public void setDataScopeConfigJson(String dataScopeConfigJson) { this.dataScopeConfigJson = dataScopeConfigJson; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
