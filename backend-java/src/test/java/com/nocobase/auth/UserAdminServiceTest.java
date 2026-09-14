package com.nocobase.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * UserAdminService 单测(Week 32).
 * 覆盖 9 个方法:list/get/create/update/resetPassword/delete/getUserRoles/assignRole/removeRole/getEffectivePermissions.
 */
class UserAdminServiceTest {

    private UserRepository userRepository;
    private UserRoleRepository userRoleRepository;
    private RoleRepository roleRepository;
    private PasswordEncoder passwordEncoder;
    private UserAdminService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userRoleRepository = mock(UserRoleRepository.class);
        roleRepository = mock(RoleRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new UserAdminService(userRepository, userRoleRepository,
                roleRepository, passwordEncoder);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode(any())).thenReturn("hashed-password");
    }

    private UserEntity makeUser() {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername("alice");
        u.setPasswordHash("hashed");
        u.setTenantId("tenant_default");
        u.setDisplayName("Alice");
        u.setEnabled(true);
        u.setCreatedAt(Instant.now());
        return u;
    }

    private RoleEntity makeRole(String name) {
        RoleEntity r = new RoleEntity();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setDescription(name + " role");
        r.setTenantId("tenant_default");
        return r;
    }

    private UserRoleEntity makeUserRole(UUID userId, UUID roleId) {
        UserRoleEntity ur = new UserRoleEntity(userId, roleId);
        return ur;
    }

    // ============ listAll / get ============

    @Test
    void listAll_returnsAll() {
        when(userRepository.findAll()).thenReturn(List.of(makeUser(), makeUser()));
        assertEquals(2, service.listAll().size());
    }

    @Test
    void get_found_returnsUser() {
        UserEntity u = makeUser();
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        assertEquals(u, service.get(u.getId()));
    }

    @Test
    void get_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.get(id));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ============ create ============

    @Test
    void create_newUser_succeeds() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        UserEntity u = service.create("alice", "pass123", "Alice Display");
        assertEquals("alice", u.getUsername());
        assertEquals("Alice Display", u.getDisplayName());
        assertTrue(u.isEnabled());
        assertEquals("tenant_default", u.getTenantId());
        verify(passwordEncoder).encode("pass123");
    }

    @Test
    void create_nullDisplayName_fallsBackToUsername() {
        when(userRepository.findByUsername("bob")).thenReturn(Optional.empty());
        UserEntity u = service.create("bob", "pass", null);
        assertEquals("bob", u.getDisplayName());
    }

    @Test
    void create_duplicateUsername_throws409() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(makeUser()));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create("alice", "p", "d"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    // ============ update ============

    @Test
    void update_displayName_applies() {
        UserEntity u = makeUser();
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        UserEntity updated = service.update(u.getId(), "New Name", null);
        assertEquals("New Name", updated.getDisplayName());
        assertTrue(updated.isEnabled());  // 不变
    }

    @Test
    void update_enabled_false_applies() {
        UserEntity u = makeUser();
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        UserEntity updated = service.update(u.getId(), null, false);
        assertFalse(updated.isEnabled());
    }

    @Test
    void update_both_applies() {
        UserEntity u = makeUser();
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        UserEntity updated = service.update(u.getId(), "New", false);
        assertEquals("New", updated.getDisplayName());
        assertFalse(updated.isEnabled());
    }

    @Test
    void update_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.update(id, "x", null));
    }

    // ============ resetPassword ============

    @Test
    void resetPassword_encodesNewPassword() {
        UserEntity u = makeUser();
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        service.resetPassword(u.getId(), "newpass");
        verify(passwordEncoder).encode("newpass");
        assertEquals("hashed-password", u.getPasswordHash());
        verify(userRepository).save(u);
    }

    @Test
    void resetPassword_userNotFound_throws404() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.resetPassword(id, "x"));
    }

    // ============ delete ============

    @Test
    void delete_callsDeleteById() {
        UUID id = UUID.randomUUID();
        service.delete(id);
        verify(userRepository).deleteById(id);
    }

    // ============ getUserRoles ============

    @Test
    void getUserRoles_returnsMappedRoles() {
        UUID userId = UUID.randomUUID();
        RoleEntity role = makeRole("admin");
        UserRoleEntity ur = makeUserRole(userId, role.getId());
        when(userRoleRepository.findByIdUserId(userId)).thenReturn(List.of(ur));
        when(roleRepository.findById(role.getId())).thenReturn(Optional.of(role));

        List<RoleEntity> roles = service.getUserRoles(userId);
        assertEquals(1, roles.size());
        assertEquals("admin", roles.get(0).getName());
    }

    @Test
    void getUserRoles_orphanRole_skipped() {
        UUID userId = UUID.randomUUID();
        UUID orphanRoleId = UUID.randomUUID();
        UserRoleEntity ur = makeUserRole(userId, orphanRoleId);
        when(userRoleRepository.findByIdUserId(userId)).thenReturn(List.of(ur));
        when(roleRepository.findById(orphanRoleId)).thenReturn(Optional.empty());

        List<RoleEntity> roles = service.getUserRoles(userId);
        assertEquals(0, roles.size());
    }

    // ============ assignRole ============

    @Test
    void assignRole_newAssignment_saves() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(userRoleRepository.findById(any())).thenReturn(Optional.empty());

        service.assignRole(userId, roleId);

        ArgumentCaptor<UserRoleEntity> cap = ArgumentCaptor.forClass(UserRoleEntity.class);
        verify(userRoleRepository).save(cap.capture());
        assertEquals(userId, cap.getValue().getId().getUserId());
        assertEquals(roleId, cap.getValue().getId().getRoleId());
    }

    @Test
    void assignRole_alreadyAssigned_noOp() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UserRoleEntity existing = makeUserRole(userId, roleId);
        when(userRoleRepository.findById(any())).thenReturn(Optional.of(existing));

        service.assignRole(userId, roleId);

        verify(userRoleRepository, times(0)).save(any());
    }

    // ============ removeRole ============

    @Test
    void removeRole_callsDeleteById() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        service.removeRole(userId, roleId);

        ArgumentCaptor<UserRoleEntity.UserRoleId> cap =
                ArgumentCaptor.forClass(UserRoleEntity.UserRoleId.class);
        verify(userRoleRepository).deleteById(cap.capture());
        assertEquals(userId, cap.getValue().getUserId());
        assertEquals(roleId, cap.getValue().getRoleId());
    }

    // ============ getEffectivePermissions ============

    @Test
    void getEffectivePermissions_returnsRolesAndPolicies() {
        UUID userId = UUID.randomUUID();
        RoleEntity admin = makeRole("admin");
        RoleEntity editor = makeRole("editor");
        UserRoleEntity ur1 = makeUserRole(userId, admin.getId());
        UserRoleEntity ur2 = makeUserRole(userId, editor.getId());
        when(userRoleRepository.findByIdUserId(userId))
                .thenReturn(List.of(ur1, ur2));
        when(roleRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(roleRepository.findById(editor.getId())).thenReturn(Optional.of(editor));

        Map<String, Object> perms = service.getEffectivePermissions(userId);

        assertEquals(userId.toString(), perms.get("user_id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roleDtos = (List<Map<String, Object>>) perms.get("roles");
        assertEquals(2, roleDtos.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policies = (List<Map<String, Object>>) perms.get("policies_summary");
        assertEquals(2, policies.size());
        // policies_summary 每个 role 一条
        assertNotNull(policies.get(0).get("role_id"));
        assertNotNull(policies.get(0).get("role_name"));
    }
}
