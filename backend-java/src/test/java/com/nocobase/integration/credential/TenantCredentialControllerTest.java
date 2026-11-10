package com.nocobase.integration.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(TenantCredentialController.class)
@Import(TenantCredentialControllerTest.MethodSecurityTestConfig.class)
class TenantCredentialControllerTest {

    private static final String TEST_VALUE = "test-only-credential-value";

    @Autowired private WebApplicationContext context;
    @MockBean private TenantCredentialService credentialService;
    @MockBean private AuditService auditService;
    @MockBean private com.nocobase.auth.JwtAuthFilter jwtAuthFilter;
    @MockBean private com.nocobase.quota.TenantQuotaFilter tenantQuotaFilter;
    @MockBean private com.nocobase.apikey.ApiKeyFilter apiKeyFilter;
    @MockBean private com.nocobase.ratelimit.GlobalRateLimitFilter globalRateLimitFilter;
    @MockBean private com.nocobase.config.SecurityConfig securityConfig;
    private MockMvc mockMvc;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(credentialService.status("jwt_tenant", "dingtalk")).thenReturn(Map.of(
                "configured", true, "status", "ACTIVE",
                "masked", Map.of("app-secret", "********"),
                "updatedAt", "2026-11-09T00:00:00Z"));
        when(credentialService.status("jwt_tenant", "feishu")).thenReturn(Map.of(
                "configured", false, "status", "NOT_CONFIGURED",
                "masked", Map.of(), "updatedAt", "2026-11-09T00:00:00Z"));
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }

    private UsernamePasswordAuthenticationToken principal(String role) {
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(userId, "tester", "jwt_tenant"), null,
                List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void nonAdmin_isForbiddenByRealMethodSecurity() throws Exception {
        mockMvc.perform(put("/api/admin/integration-credentials/dingtalk")
                        .with(authentication(principal("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        verify(credentialService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void upsert_ignoresBodyTenant_neverReturnsOrAuditsSecrets() throws Exception {
        String body = "{\"app-secret\":\"" + TEST_VALUE
                + "\",\"tenantId\":\"other_tenant\",\"tenant\":\"another_tenant\"}";
        String response = mockMvc.perform(put("/api/admin/integration-credentials/dingtalk")
                        .with(authentication(principal("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.masked.app-secret").value("********"))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-09T00:00:00Z"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(TEST_VALUE, "appSecretEnc", "plaintextSecret", "tenantId");
        verify(credentialService).saveAll("jwt_tenant", "dingtalk", Map.of("app-secret", TEST_VALUE),
                userId.toString());
        verify(credentialService, never()).saveAll(eq("other_tenant"), any(), any(), any());
        verify(credentialService, never()).saveAll(eq("another_tenant"), any(), any(), any());

        ArgumentCaptor<Object> auditPayload = ArgumentCaptor.forClass(Object.class);
        verify(auditService).log(eq("jwt_tenant"), eq(userId), eq("tester"),
                eq("integration.credential.upsert"), eq("integration_credential"), eq("dingtalk"),
                auditPayload.capture());
        assertThat(auditPayload.getValue()).isEqualTo(
                Map.of("provider", "dingtalk", "keys", java.util.Set.of("app-secret"), "action", "upsert"));
        assertThat(String.valueOf(auditPayload.getValue())).doesNotContain(TEST_VALUE);
    }

    @Test
    void upsert_rejectsUnknownProviderKeyAndBlankValues() throws Exception {
        mockMvc.perform(put("/api/admin/integration-credentials/dingtalk")
                        .with(authentication(principal("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"encrypt-key\":\"" + TEST_VALUE + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/admin/integration-credentials/feishu")
                        .with(authentication(principal("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"app-key\":\"" + TEST_VALUE + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/admin/integration-credentials/dingtalk")
                        .with(authentication(principal("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"app-secret\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/admin/integration-credentials/other")
                        .with(authentication(principal("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(credentialService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void statusAndDisable_returnSafeStateAndDisableOnlyJwtTenant() throws Exception {
        when(credentialService.status("jwt_tenant", "feishu")).thenReturn(
                Map.of("configured", true, "status", "DISABLED",
                        "masked", Map.of("app-secret", "********"),
                        "updatedAt", "2026-11-09T00:00:00Z"));

        mockMvc.perform(get("/api/admin/integration-credentials/feishu")
                        .with(authentication(principal("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.masked.app-secret").value("********"));
        mockMvc.perform(post("/api/admin/integration-credentials/feishu/disable")
                        .with(authentication(principal("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        verify(credentialService).disable("jwt_tenant", "feishu", userId.toString());
        verify(auditService).log("jwt_tenant", userId, "tester",
                "integration.credential.disable", "integration_credential", "feishu",
                Map.of("provider", "feishu", "key", "*", "action", "disable"));
    }
}
