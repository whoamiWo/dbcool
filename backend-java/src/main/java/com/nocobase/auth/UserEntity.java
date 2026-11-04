package com.nocobase.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 用户实体.
 *
 * <p>Week 4 脚手架版:固定字段,不带 role 关联(Phase 4 加 RBAC).
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    /**
     * 本地密码哈希。
     *
     * <p>V77 起该列放开 NOT NULL：外部托管账号（钉钉/企微/飞书/LDAP/OIDC）没有本地密码，
     * 只能走 SSO。判定依据是 {@link #passwordSet}，不要用本字段是否为 null 来区分 ——
     * 存量账号可能持有空哈希（A-2 的历史数据形态）。
     */
    @Column(name = "password_hash")
    private String passwordHash;

    /** 是否设置过本地密码。false = 外部平台托管，仅允许 SSO 登录。 */
    @Column(name = "password_set", nullable = false)
    private boolean passwordSet = true;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "email")
    private String email;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // ---------- E1 组织/档案字段（V77） ----------

    /** 所属部门（org_unit.id）。为空时数据范围等同 SELF（决策 D6）。 */
    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "position")
    private String position;

    /** 直属上级（users.id），approverType=MANAGER 的解析来源。 */
    @Column(name = "manager_id")
    private UUID managerId;

    @Column(name = "phone")
    private String phone;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "employee_no")
    private String employeeNo;

    /** 人员状态：ACTIVE / RESIGNED / DISABLED。 */
    @Column(name = "status")
    private String status = UserStatus.ACTIVE;

    @Column(name = "joined_at")
    private Instant joinedAt;

    public UserEntity() {}

    public UserEntity(UUID id, String username, String passwordHash, String tenantId,
                      String displayName, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.tenantId = tenantId;
        this.displayName = displayName;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean isPasswordSet() { return passwordSet; }
    public void setPasswordSet(boolean passwordSet) { this.passwordSet = passwordSet; }

    public UUID getDepartmentId() { return departmentId; }
    public void setDepartmentId(UUID departmentId) { this.departmentId = departmentId; }

    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }

    public UUID getManagerId() { return managerId; }
    public void setManagerId(UUID managerId) { this.managerId = managerId; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }

    /** 可登录/可被检索的人员（ACTIVE 且 enabled）。 */
    public boolean isActiveEmployee() {
        return enabled && !UserStatus.RESIGNED.equals(status) && !UserStatus.DISABLED.equals(status);
    }
}
