package com.nocobase.auth.oidc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nocobase.auth.UserEntity;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class OidcAuthControllerTest {

    private final OidcLoginCodeService service = mock(OidcLoginCodeService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new OidcAuthController(service, mock(com.nocobase.audit.AuditService.class)))
            .setControllerAdvice(new com.nocobase.config.GlobalExceptionHandler())
            .build();

    @Test
    void exchange_returnsFrontendCompatibleTokenAndUserPayload() throws Exception {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        user.setDisplayName("Alice");
        user.setTenantId("tenant_default");
        when(service.exchange("one-time-code")).thenReturn(
                new OidcLoginCodeService.ExchangeResult("LOCAL.jwt", 900, user, List.of("editor")));

        mockMvc.perform(post("/api/auth/oidc/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"one-time-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.access_token").value("LOCAL.jwt"))
                .andExpect(jsonPath("$.data.accessToken").value("LOCAL.jwt"))
                .andExpect(jsonPath("$.data.token_type").value("Bearer"))
                .andExpect(jsonPath("$.data.expires_in").value(900))
                .andExpect(jsonPath("$.data.user.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.user.username").value("alice"))
                .andExpect(jsonPath("$.data.user.tenant_id").value("tenant_default"))
                .andExpect(jsonPath("$.data.user.roles[0]").value("editor"))
                .andExpect(jsonPath("$.data.refresh_token").doesNotExist());
    }

    @Test
    void exchange_invalidExpiredOrReplayedCode_returns401() throws Exception {
        when(service.exchange("bad-code")).thenThrow(
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC 登录 code 无效或已过期"));

        mockMvc.perform(post("/api/auth/oidc/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bad-code\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
