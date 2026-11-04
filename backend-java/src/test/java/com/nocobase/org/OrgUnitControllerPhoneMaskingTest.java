package com.nocobase.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

/**
 * 决策 D7：手机号只对 ADMIN/HR 下发。
 *
 * <p>断言的是「响应字段是否存在」，而不是前端是否隐藏 —— 前端隐藏属于伪权限。
 */
class OrgUnitControllerPhoneMaskingTest {

    private OrgUnitService orgUnitService;
    private UserRepository userRepository;
    private OrgUnitController controller;

    private final UUID targetId = UUID.randomUUID();
    private final AuthenticatedUser caller =
            new AuthenticatedUser(UUID.randomUUID(), "bob", "t1");

    @BeforeEach
    void setUp() {
        orgUnitService = mock(OrgUnitService.class);
        userRepository = mock(UserRepository.class);
        controller = new OrgUnitController(orgUnitService, userRepository);

        UserEntity target = new UserEntity();
        target.setId(targetId);
        target.setUsername("alice");
        target.setDisplayName("Alice");
        target.setPhone("13800000000");
        target.setTenantId("t1");
        target.setEnabled(true);
        when(userRepository.findByIdAndTenantId(targetId, "t1")).thenReturn(Optional.of(target));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String role) {
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(caller, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
    }

    @Test
    @DisplayName("普通用户：响应不含 phone 字段")
    void member_plainUser_phoneOmitted() {
        authenticate("USER");

        Map<String, Object> resp = controller.member(targetId, caller);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data).doesNotContainKey("phone");
        assertThat(data).containsEntry("display_name", "Alice");
    }

    @Test
    @DisplayName("ADMIN：响应包含 phone 字段")
    void member_admin_phoneIncluded() {
        authenticate("ADMIN");

        Map<String, Object> resp = controller.member(targetId, caller);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data).containsEntry("phone", "13800000000");
    }
}
