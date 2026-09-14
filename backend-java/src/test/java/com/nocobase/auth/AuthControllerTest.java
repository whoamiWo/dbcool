package com.nocobase.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.config.SecurityConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AuthController WebMvcTest(Week 27 抬红线).
 * 覆盖 /api/auth 4 个端点:
 *   POST /api/auth/login
 *   POST /api/auth/refresh
 *   POST /api/auth/password
 *   GET  /api/auth/me
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private UserRepository userRepository;
    @MockBean private UserRoleRepository userRoleRepository;
    @MockBean private RoleRepository roleRepository;
    @MockBean private PasswordEncoder passwordEncoder;
    @MockBean private JwtService jwtService;
    @MockBean private RefreshTokenService refreshTokenService;

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void loginAs(UUID userId, String tenantId) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, "alice", tenantId);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private UserEntity user(UUID id, String username) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername(username);
        u.setPasswordHash("$2a$10$hash");
        u.setDisplayName("Display " + username);
        u.setTenantId("tenant_default");
        u.setEnabled(true);
        u.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));
        return u;
    }

    // ============ login ============

    @Test
    void login_validCredentials_returnsTokens() throws Exception {
        UUID uid = UUID.randomUUID();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(uid, "alice")));
        when(passwordEncoder.matches("pw", "$2a$10$hash")).thenReturn(true);
        when(jwtService.issueAccessToken(uid, "alice", "tenant_default")).thenReturn("ACCESS.jwt");
        when(refreshTokenService.issue(uid)).thenReturn("REFRESH.token");
        when(jwtService.getAccessTtl()).thenReturn(Duration.ofHours(1));

        String body = """
                {"username":"alice","password":"pw"}
                """;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.access_token").value("ACCESS.jwt"))
                .andExpect(jsonPath("$.data.refresh_token").value("REFRESH.token"))
                .andExpect(jsonPath("$.data.token_type").value("Bearer"))
                .andExpect(jsonPath("$.data.expires_in").value(3600))
                .andExpect(jsonPath("$.data.user.username").value("alice"))
                .andExpect(jsonPath("$.data.user.id").value(uid.toString()));
    }

    @Test
    void login_userNotFound_returns401() throws Exception {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        String body = """
                {"username":"ghost","password":"pw"}
                """;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        UUID uid = UUID.randomUUID();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(uid, "alice")));
        when(passwordEncoder.matches("bad", "$2a$10$hash")).thenReturn(false);

        String body = """
                {"username":"alice","password":"bad"}
                """;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_blankUsername_returns400() throws Exception {
        String body = """
                {"username":"","password":"pw"}
                """;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ============ refresh ============

    @Test
    void refresh_validToken_returnsNewTokens() throws Exception {
        UUID uid = UUID.randomUUID();
        when(refreshTokenService.consume("REFRESH.token")).thenReturn(uid);
        when(userRepository.findById(uid)).thenReturn(Optional.of(user(uid, "alice")));
        when(jwtService.issueAccessToken(uid, "alice", "tenant_default")).thenReturn("NEW.jwt");
        when(refreshTokenService.issue(uid)).thenReturn("NEW.REFRESH");
        when(jwtService.getAccessTtl()).thenReturn(Duration.ofHours(1));

        String body = """
                {"refreshToken":"REFRESH.token"}
                """;
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.access_token").value("NEW.jwt"))
                .andExpect(jsonPath("$.data.refresh_token").value("NEW.REFRESH"))
                .andExpect(jsonPath("$.data.token_type").value("Bearer"));
    }

    @Test
    void refresh_invalidToken_returns401() throws Exception {
        when(refreshTokenService.consume("BAD")).thenReturn(null);

        String body = """
                {"refreshToken":"BAD"}
                """;
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_userNotFound_returns401() throws Exception {
        UUID uid = UUID.randomUUID();
        when(refreshTokenService.consume("OK")).thenReturn(uid);
        when(userRepository.findById(uid)).thenReturn(Optional.empty());

        String body = """
                {"refreshToken":"OK"}
                """;
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    // ============ change password ============

    @Test
    void changePassword_validOldPassword_updatesAndSaves() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid, "tenant_default");
        UserEntity u = user(uid, "alice");
        when(userRepository.findById(uid)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("oldpw", "$2a$10$hash")).thenReturn(true);
        when(passwordEncoder.encode("newpw")).thenReturn("$2a$10$newhash");
        when(userRepository.save(any())).thenReturn(u);

        String body = """
                {"oldPassword":"oldpw","newPassword":"newpw"}
                """;
        mockMvc.perform(post("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("密码修改成功"));
        verify(userRepository).save(any());
        verify(passwordEncoder).encode("newpw");
    }

    @Test
    void changePassword_wrongOldPassword_returns401() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid, "tenant_default");
        when(userRepository.findById(uid)).thenReturn(Optional.of(user(uid, "alice")));
        when(passwordEncoder.matches("bad", "$2a$10$hash")).thenReturn(false);

        String body = """
                {"oldPassword":"bad","newPassword":"newpw"}
                """;
        mockMvc.perform(post("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_userNotFound_returns404() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid, "tenant_default");
        when(userRepository.findById(uid)).thenReturn(Optional.empty());

        String body = """
                {"oldPassword":"oldpw","newPassword":"newpw"}
                """;
        mockMvc.perform(post("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ============ me ============

    @Test
    void me_returnsUserInfoAndRoles() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        loginAs(uid, "tenant_default");
        UserEntity u = user(uid, "alice");
        when(userRepository.findById(uid)).thenReturn(Optional.of(u));
        UserRoleEntity ur = new UserRoleEntity(uid, roleId);
        when(userRoleRepository.findByIdUserId(uid)).thenReturn(List.of(ur));
        RoleEntity role = new RoleEntity();
        role.setId(roleId);
        role.setName("admin");
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.tenant_id").value("tenant_default"))
                .andExpect(jsonPath("$.data.roles[0]").value("admin"));
    }

    @Test
    void me_userNotFound_returns404() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid, "tenant_default");
        when(userRepository.findById(uid)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isNotFound());
    }
}
