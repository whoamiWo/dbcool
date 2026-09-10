package com.nocobase.auth;

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

    public UserAdminService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<UserEntity> listAll() {
        return userRepository.findAll();
    }

    public UserEntity get(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User 不存在"));
    }

    @Transactional
    public UserEntity create(String username, String password, String displayName) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setTenantId("tenant_default");
        u.setDisplayName(displayName != null ? displayName : username);
        u.setEnabled(true);
        u.setCreatedAt(Instant.now());
        return userRepository.save(u);
    }

    @Transactional
    public UserEntity update(UUID id, String displayName, Boolean enabled) {
        UserEntity u = get(id);
        if (displayName != null) u.setDisplayName(displayName);
        if (enabled != null) u.setEnabled(enabled);
        return userRepository.save(u);
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        UserEntity u = get(id);
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(u);
    }

    @Transactional
    public void delete(UUID id) {
        userRepository.deleteById(id);
    }

    public List<RoleEntity> getUserRoles(UUID userId) {
        return userRoleRepository.findByIdUserId(userId).stream()
                .map(ur -> roleRepository.findById(ur.getId().getRoleId()).orElse(null))
                .filter(r -> r != null)
                .toList();
    }

    @Transactional
    public void assignRole(UUID userId, UUID roleId) {
        if (userRoleRepository.findById(new UserRoleEntity.UserRoleId(userId, roleId)).isPresent()) {
            return; // 已存在
        }
        userRoleRepository.save(new UserRoleEntity(userId, roleId));
    }

    @Transactional
    public void removeRole(UUID userId, UUID roleId) {
        userRoleRepository.deleteById(new UserRoleEntity.UserRoleId(userId, roleId));
    }

    /**
     * 用户的有效权限(US-307 权限预览) — 简化为角色列表 + ACL 列表.
     */
    public Map<String, Object> getEffectivePermissions(UUID userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("user_id", userId.toString());
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
