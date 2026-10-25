package com.nocobase.auth.oidc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE10: OIDC 用户映射 / JIT 建号测试。
 *
 * <p>覆盖：
 * 1. 按 email 匹配已有用户
 * 2. 按 username 匹配已有用户
 * 3. 均未匹配 → JIT 建号（且不设密码）
 * 4. 已禁用用户 → 403 拒绝
 * 5. 用户名为空时用 sub 兜底
 * 6. null / 无标识用户 → 401
 */
class OidcUserServiceTest {

    private UserRepository userRepository;
    private OidcUserService service;

    private static final String TENANT = "tenant_default";

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new OidcUserService(userRepository);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private OidcUser oidcUser(String sub, String email, String username, String fullName) {
        // DefaultOidcUser 需要至少 authorities；idToken 构造需要 claims
        OidcIdToken idToken = new OidcIdToken(
                "token-value",
                null,
                null,
                java.util.Map.of(
                        "sub", sub,
                        "email", email == null ? "" : email,
                        "preferred_username", username == null ? "" : username,
                        "name", fullName == null ? "" : fullName));
        return new DefaultOidcUser(
                java.util.List.of(new org.springframework.security.core.authority
                        .SimpleGrantedAuthority("ROLE_USER")),
                idToken);
    }

    private UserEntity existingUser(String username, String email, boolean enabled) {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername(username);
        u.setEmail(email);
        u.setTenantId(TENANT);
        u.setEnabled(enabled);
        return u;
    }

    @Test
    void mapOrCreate_matchesByEmail() {
        UserEntity existing = existingUser("zhangsan", "z@corp.com", true);
        when(userRepository.findByEmail("z@corp.com")).thenReturn(Optional.of(existing));
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());

        UserEntity result = service.mapOrCreate(oidcUser("sub-1", "z@corp.com", "other", "张三"));

        assertEquals(existing.getId(), result.getId());
    }

    @Test
    void mapOrCreate_matchesByUsername() {
        UserEntity existing = existingUser("lisi", "l@corp.com", true);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByUsername("lisi")).thenReturn(Optional.of(existing));

        UserEntity result = service.mapOrCreate(oidcUser("sub-2", "unknown@corp.com", "lisi", "李四"));

        assertEquals(existing.getId(), result.getId());
    }

    @Test
    void mapOrCreate_createsUser_whenNoMatch() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());

        UserEntity created = service.mapOrCreate(
                oidcUser("sub-new", "new@corp.com", "newuser", "新用户"));

        assertNotNull(created.getId());
        assertEquals("newuser", created.getUsername());
        assertEquals("new@corp.com", created.getEmail());
        // JIT 用户不得设置密码 —— 只能走 OIDC 登录
        assertNull(created.getPasswordHash());
        assertNotNull(created.getTenantId());
    }

    @Test
    void mapOrCreate_rejectsDisabledUser() {
        UserEntity disabled = existingUser("disabled", "d@corp.com", false);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(disabled));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.mapOrCreate(oidcUser("sub-3", "d@corp.com", "disabled", "禁用")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void mapOrCreate_fallsBackToSubject_whenUsernameBlank() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());

        // email 与 username 均为空 → 用 sub 兜底
        OidcIdToken idToken = new OidcIdToken("tv", null, null,
                java.util.Map.of("sub", "only-sub-123"));
        OidcUser user = new DefaultOidcUser(
                java.util.List.of(new org.springframework.security.core.authority
                        .SimpleGrantedAuthority("ROLE_USER")),
                idToken);

        UserEntity created = service.mapOrCreate(user);

        assertEquals("only-sub-123", created.getUsername());
    }

    @Test
    void mapOrCreate_throws401_whenUserNull() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.mapOrCreate(null));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void rolesOf_returnsEmptyList() {
        // 当前未接入角色映射，返回空列表（调用方按需扩展）
        assertEquals(0, service.rolesOf(UUID.randomUUID(), TENANT).size());
    }
}
