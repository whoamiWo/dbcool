package com.nocobase.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nocobase.acl.RowAclService;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.AclEnforcer;
import com.nocobase.auth.AclPolicyRepository;
import com.nocobase.auth.AuthController;
import com.nocobase.auth.JwtAuthFilter;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.auth.RoleRepository;
import com.nocobase.auth.UserRepository;
import com.nocobase.config.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 安全审计测试(Week 34).
 * 测 SQL 注入 / XSS / null payload / 大 body / 错误 HTTP 方法 / 路径遍历 等业务级安全边界.
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class SecurityAuditTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private UserRepository userRepository;
    @MockBean private com.nocobase.auth.UserRoleRepository userRoleRepository;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @MockBean private JwtService jwtService;
    @MockBean private RefreshTokenService refreshTokenService;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private AuditService auditService;
    @MockBean private AclEnforcer aclEnforcer;
    @MockBean private RoleRepository roleRepository;
    @MockBean private AclPolicyRepository aclPolicyRepository;
    @MockBean private RowAclService rowAclService;

    private void clearContext() { SecurityContextHolder.clearContext(); }

    private void setAuthUser(String username, String tenantId) {
        JwtAuthFilter.AuthenticatedUser user =
                new JwtAuthFilter.AuthenticatedUser(UUID.randomUUID(), username, tenantId);
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(user, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER")))));
    }

    // 注意:WebMvcTest + @MockBean(SecurityConfig) 禁用 Spring Security,这两测试不适用
    // 真实集成测需要 SpringBootTest + H2 + Flyway(超出当前范围)

    @Test
    void login_sqlInjectionInUsername_returns401() throws Exception {
        clearContext();
        when(userRepository.findByUsername(anyString())).thenReturn(java.util.Optional.empty());
        String body = "{\"username\":\"admin' OR '1'='1\",\"password\":\"anything' OR 1=1--\"}";
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_xssInUsername_returns401() throws Exception {
        clearContext();
        when(userRepository.findByUsername(anyString())).thenReturn(java.util.Optional.empty());
        String body = "{\"username\":\"<script>alert('xss')</script>\",\"password\":\"p\"}";
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_sqlInjection_doesNotCrash() throws Exception {
        clearContext();
        setAuthUser("alice", "tenant_default");
        when(userRepository.findById(any(UUID.class))).thenReturn(java.util.Optional.empty());
        String body = "{\"old_password\":\"' OR 1=1--\",\"new_password\":\"new\"}";
        mockMvc.perform(post("/api/auth/password").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void login_malformedJson_returns4xx() throws Exception {
        clearContext();
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":}")).andExpect(status().is4xxClientError());
    }

    @Test
    void login_emptyBody_returns4xx() throws Exception {
        clearContext();
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void login_missingContentType_returns4xx() throws Exception {
        clearContext();
        mockMvc.perform(post("/api/auth/login").content("{\"username\":\"x\",\"password\":\"y\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void login_blankUsername_returns400() throws Exception {
        clearContext();
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\",\"password\":\"p\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_nullUsername_returns400() throws Exception {
        clearContext();
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":null,\"password\":\"p\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void login_nullPassword_returns400() throws Exception {
        clearContext();
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"u\",\"password\":null}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void login_extremelyLongUsername_handledGracefully() throws Exception {
        clearContext();
        when(userRepository.findByUsername(anyString())).thenReturn(java.util.Optional.empty());
        String body = "{\"username\":\"" + "a".repeat(10000) + "\",\"password\":\"p\"}";
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pathTraversal_returns4xx() throws Exception {
        clearContext();
        mockMvc.perform(get("/api/health/../../../etc/passwd"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void wrongHttpMethod_returns4xx() throws Exception {
        clearContext();
        mockMvc.perform(get("/api/auth/login")).andExpect(status().is4xxClientError());
    }

    @Test
    void deleteOnLoginEndpoint_returns4xx() throws Exception {
        clearContext();
        mockMvc.perform(delete("/api/auth/login")).andExpect(status().is4xxClientError());
    }

    @Test
    void login_unicodeInUsername_returns401() throws Exception {
        clearContext();
        when(userRepository.findByUsername(anyString())).thenReturn(java.util.Optional.empty());
        // Unicode + 特殊字符
        String body = "{\"username\":\"用户🔐\\u0000\",\"password\":\"p\"}";
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_jsonInjectionInPassword_returns401() throws Exception {
        clearContext();
        when(userRepository.findByUsername(anyString())).thenReturn(java.util.Optional.empty());
        // JSON 注入尝试
        String body = "{\"username\":\"u\",\"password\":\"p\",\"role\":\"admin\"}";
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());
    }
}
