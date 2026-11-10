package com.nocobase.auth.oidc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.RoleRepository;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserProvisioningService;
import com.nocobase.auth.UserRepository;
import com.nocobase.auth.UserStatus;
import com.nocobase.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ResponseStatusException;

class OidcUserServiceTest {

    private static final String TENANT = "tenant_default";

    private UserRepository userRepository;
    private UserProvisioningService provisioningService;
    private RoleRepository roleRepository;
    private OidcUserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        provisioningService = mock(UserProvisioningService.class);
        roleRepository = mock(RoleRepository.class);
        when(provisioningService.createExternal(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    UserEntity user = new UserEntity();
                    user.setId(UUID.randomUUID());
                    user.setTenantId(inv.getArgument(0));
                    user.setUsername(inv.getArgument(1));
                    user.setDisplayName(inv.getArgument(2));
                    user.setEmail(inv.getArgument(3));
                    user.setExternalSource(inv.getArgument(4));
                    user.setExternalId(inv.getArgument(5));
                    user.setPasswordSet(false);
                    return user;
                });
        service = new OidcUserService(userRepository, provisioningService, roleRepository);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private OidcUser oidcUser(String sub, String email, String username, String fullName) {
        java.util.Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", sub);
        if (email != null) claims.put("email", email);
        if (username != null) claims.put("preferred_username", username);
        if (fullName != null) claims.put("name", fullName);
        OidcIdToken idToken = new OidcIdToken("token-value", null, null, claims);
        return new DefaultOidcUser(List.of(), idToken);
    }

    private UserEntity boundUser(String subject) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("oidc-user");
        user.setEmail("oidc@corp.com");
        user.setTenantId(TENANT);
        user.setExternalSource("OIDC");
        user.setExternalId(subject);
        user.setEnabled(true);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    @Test
    void mapOrCreate_sameEmailAndUsernameWithNewSub_createsInsteadOfTakingOverLocalAccount() {
        UserEntity local = boundUser("another-sub");
        local.setUsername("zhangsan");
        local.setEmail("z@corp.com");
        local.setExternalSource(null);
        local.setExternalId("another-sub");
        local.setPasswordSet(true);
        TenantContext.set("tenant_other");
        when(userRepository.findByTenantIdAndExternalSourceAndExternalId(TENANT, "OIDC", "new-sub"))
                .thenReturn(Optional.empty());

        UserEntity created = service.mapOrCreate(
                oidcUser("new-sub", "z@corp.com", "zhangsan", "张三"));

        assertNotEquals(local.getId(), created.getId());
        assertTrue(created.getUsername().startsWith("oidc_"));
        assertNotEquals(local.getUsername(), created.getUsername());
        assertEquals(TENANT, created.getTenantId());
        assertEquals("OIDC", created.getExternalSource());
        assertEquals("new-sub", created.getExternalId());
        assertNull(created.getPasswordHash());
        assertEquals("zhangsan", local.getUsername());
        assertEquals("another-sub", local.getExternalId());
        verify(userRepository, never()).findByTenantIdAndEmail(any(), any());
        verify(userRepository, never()).findByTenantIdAndUsername(any(), any());
        verify(provisioningService).createExternal(
                eq(TENANT), eq(created.getUsername()), eq("张三"), eq("z@corp.com"),
                eq("OIDC"), eq("new-sub"));
    }

    @Test
    void mapOrCreate_repeatedLogin_matchesSubWithoutAnotherJit() {
        UserEntity bound = boundUser("repeat-sub");
        TenantContext.set("tenant_other");
        when(userRepository.findByTenantIdAndExternalSourceAndExternalId(TENANT, "OIDC", "repeat-sub"))
                .thenReturn(Optional.of(bound));

        UserEntity first = service.mapOrCreate(oidcUser("repeat-sub", "new@corp.com", "new-name", "新名"));
        UserEntity second = service.mapOrCreate(oidcUser("repeat-sub", "new@corp.com", "new-name", "新名"));

        assertEquals(bound.getId(), first.getId());
        assertEquals(first.getId(), second.getId());
        verify(userRepository, times(2))
                .findByTenantIdAndExternalSourceAndExternalId(TENANT, "OIDC", "repeat-sub");
        verify(provisioningService, never()).createExternal(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mapOrCreate_rejectsBoundDisabledUser() {
        UserEntity disabled = boundUser("disabled-sub");
        disabled.setEnabled(false);
        when(userRepository.findByTenantIdAndExternalSourceAndExternalId(TENANT, "OIDC", "disabled-sub"))
                .thenReturn(Optional.of(disabled));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.mapOrCreate(oidcUser("disabled-sub", "other@corp.com", "other", null)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(provisioningService, never()).createExternal(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mapOrCreate_rejectsBoundResignedUser() {
        UserEntity resigned = boundUser("resigned-sub");
        resigned.setStatus(UserStatus.RESIGNED);
        when(userRepository.findByTenantIdAndExternalSourceAndExternalId(TENANT, "OIDC", "resigned-sub"))
                .thenReturn(Optional.of(resigned));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.mapOrCreate(oidcUser("resigned-sub", null, null, null)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(provisioningService, never()).createExternal(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mapOrCreate_subjectOnly_createsExternalUserWithoutPassword() {
        UserEntity created = service.mapOrCreate(oidcUser("only-sub-123", null, null, null));

        assertNotNull(created.getId());
        assertTrue(created.getUsername().startsWith("oidc_"));
        assertEquals(TENANT, created.getTenantId());
        assertEquals("only-sub-123", created.getExternalId());
        assertNull(created.getPasswordHash());
        assertEquals(false, created.isPasswordSet());
    }

    @Test
    void mapOrCreate_throws401_whenUserOrSubjectMissing() {
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> service.mapOrCreate(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> service.mapOrCreate(oidcUser("", null, "alice", null))).getStatusCode());
        verify(provisioningService, never()).createExternal(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rolesOf_readsOnlyLocalRolesForDefaultTenant() {
        UUID userId = UUID.randomUUID();
        when(roleRepository.findRoleNamesByUserId(userId, TENANT)).thenReturn(List.of("editor"));

        assertEquals(List.of("editor"), service.rolesOf(userId));
        verify(roleRepository).findRoleNamesByUserId(userId, TENANT);
    }
}
