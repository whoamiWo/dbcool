package com.nocobase.auth;

import com.nocobase.tenant.TenantContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 用户管理服务(US-301).
 */
@Service
public class UserAdminService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    /** A-5：禁用/删除时立即失效会话（含 refresh token） */
    private final UserStatusService userStatusService;

    public UserAdminService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            UserStatusService userStatusService
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.userStatusService = userStatusService;
    }

    public List<UserEntity> listAll(String tenantId) {
        return userRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public UserEntity get(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User 不存在"));
    }

    public UserEntity get(UUID id, String tenantId) {
        return userRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User 不存在"));
    }

    @Transactional
    public UserEntity create(String username, String password, String displayName, String tenantId) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setPasswordSet(true);
        // Week 41 复核:改用 TenantContext,不再硬编码。
        // [PHASE78 R1] 显式传 tenantId,与 update/resetPassword/delete 对齐(不依赖 ThreadLocal)
        u.setTenantId(tenantId != null ? tenantId : TenantContext.currentTenantId());
        u.setDisplayName(displayName != null ? displayName : username);
        u.setEnabled(true);
        u.setCreatedAt(Instant.now());
        return userRepository.save(u);
    }

    @Transactional
    public UserEntity update(UUID id, String displayName, Boolean enabled, String tenantId) {
        UserEntity u = get(id, tenantId);
        if (displayName != null) u.setDisplayName(displayName);
        boolean disabling = enabled != null && !enabled && u.isEnabled();
        if (enabled != null) u.setEnabled(enabled);
        UserEntity saved = userRepository.save(u);
        // A-5：禁用立即失效（缓存 + 全部 refresh token），不等 60s 窗口
        if (disabling) userStatusService.invalidate(id);
        return saved;
    }

    /**
     * 扩展资料更新（E1 组织字段）。
     *
     * <p>手机号等字段由管理端（ADMIN）维护；通讯录侧的可见性由 D7 字段级过滤控制。
     */
    @Transactional
    public UserEntity updateProfile(UUID id, String tenantId, UserProfilePatch patch) {
        UserEntity u = get(id, tenantId);
        if (patch.displayName() != null) u.setDisplayName(patch.displayName());
        if (patch.email() != null) u.setEmail(patch.email());
        if (patch.phone() != null) u.setPhone(patch.phone());
        if (patch.position() != null) u.setPosition(patch.position());
        if (patch.employeeNo() != null) u.setEmployeeNo(patch.employeeNo());
        if (patch.avatarUrl() != null) u.setAvatarUrl(patch.avatarUrl());
        if (patch.managerId() != null) u.setManagerId(patch.managerId());
        if (patch.departmentId() != null) u.setDepartmentId(patch.departmentId());
        if (patch.status() != null) u.setStatus(patch.status());
        boolean disabling = patch.enabled() != null && !patch.enabled() && u.isEnabled();
        if (patch.enabled() != null) u.setEnabled(patch.enabled());
        if (UserStatus.RESIGNED.equals(u.getStatus()) || UserStatus.DISABLED.equals(u.getStatus())) {
            u.setEnabled(false);
            disabling = true;
        }
        UserEntity saved = userRepository.save(u);
        if (disabling) userStatusService.invalidate(id);
        return saved;
    }

    /** 用户资料增量更新（null = 不修改）。 */
    public record UserProfilePatch(
            String displayName, String email, String phone, String position,
            String employeeNo, String avatarUrl, UUID managerId, UUID departmentId,
            String status, Boolean enabled) {}

    @Transactional
    public void resetPassword(UUID id, String newPassword, String tenantId) {
        UserEntity u = get(id, tenantId);
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        u.setPasswordSet(true);
        userRepository.save(u);
    }

    @Transactional
    public void delete(UUID id, String tenantId) {
        UserEntity u = get(id, tenantId);
        userRepository.delete(u);
        // A-5：删除后旧 token / refresh 立即失效
        userStatusService.invalidate(id);
    }

    public List<RoleEntity> getUserRoles(UUID userId) {
        return userRoleRepository.findByIdUserId(userId).stream()
                .map(ur -> roleRepository.findById(ur.getId().getRoleId()).orElse(null))
                .filter(r -> r != null)
                .toList();
    }

    /**
     * 赋角色 —— PHASE95 F1：用户与角色双方都必须属于当前租户。
     * 缺任一条校验，租户 A 的管理员就能给租户 B 的用户赋任意角色（含 admin）。
     */
    @Transactional
    public void assignRole(UUID userId, UUID roleId, String tenantId) {
        userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "用户不存在或不属于当前租户: " + userId));
        roleRepository.findByIdAndTenantId(roleId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "角色不存在或不属于当前租户: " + roleId));
        if (userRoleRepository.findById(new UserRoleEntity.UserRoleId(userId, roleId)).isPresent()) {
            return; // 已存在
        }
        userRoleRepository.save(new UserRoleEntity(userId, roleId));
    }

    /** 撤角色 —— 同 assignRole，双向租户校验。 */
    @Transactional
    public void removeRole(UUID userId, UUID roleId, String tenantId) {
        userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "用户不存在或不属于当前租户: " + userId));
        roleRepository.findByIdAndTenantId(roleId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "角色不存在或不属于当前租户: " + roleId));
        userRoleRepository.deleteById(new UserRoleEntity.UserRoleId(userId, roleId));
    }

    /**
     * 用户的有效权限(US-307 权限预览) — 简化为角色列表 + ACL 列表.
     */
    public Map<String, Object> getEffectivePermissions(UUID userId, String tenantId) {
        Map<String, Object> result = new HashMap<>();
        result.put("user_id", userId.toString());
        result.put("tenant_id", tenantId);
        result.put("roles", getUserRoles(userId).stream().map(this::toRoleDto).toList());
        // ACL policies from user's roles
        // 简化:列出所有 role 的所有 policy
        List<Map<String, Object>> policies = new java.util.ArrayList<>();
        for (RoleEntity role : getUserRoles(userId)) {
            // AclPolicyRepository 通过 roleId 查 — 这里先简化,直接用 roleId
            policies.add(Map.of("role_id", role.getId().toString(), "role_name", role.getName()));
        }
        result.put("policies_summary", policies);
        return result;
    }

    private Map<String, Object> toRoleDto(RoleEntity r) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", r.getId().toString());
        dto.put("name", r.getName());
        dto.put("description", r.getDescription() != null ? r.getDescription() : "");
        dto.put("tenant_id", r.getTenantId());
        return dto;
    }
}
