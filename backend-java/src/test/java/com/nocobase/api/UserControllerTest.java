package com.nocobase.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;

import com.nocobase.auth.JwtAuthFilter;
import com.nocobase.config.SecurityConfig;

/**
 * UserController WebMvcTest(Week 23 抬红线).
 *
 * <p>覆盖 GET /api/users/me 两个分支(user 存在 / 不存在)。
 */
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)  // 跳过 Security 链
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SecurityConfig securityConfig;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @Test
    void me_withAuthenticatedUser_returnsUserInfo() throws Exception {
        UUID userId = UUID.randomUUID();
        // 把 Authentication 放入 SecurityContext 让 controller @AuthenticationPrincipal 能拿到
        AuthenticatedUser principal = new AuthenticatedUser(userId, "alice", "tenant_default");
        Authentication auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.tenant_id").value("tenant_default"))
                .andExpect(jsonPath("$.data.roles[0]").value("admin"));

        SecurityContextHolder.clearContext();
    }

    @Test
    void me_withoutAuthentication_returnsCode1001() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("未认证"))
                .andExpect(jsonPath("$.data").isMap());
    }
}