package com.nocobase.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OidcLoginCodeServiceTest {

    private OidcLoginCodeRepository loginCodes;
    private UserRepository users;
    private JwtService jwtService;
    private OidcLoginCodeService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        loginCodes = mock(OidcLoginCodeRepository.class);
        users = mock(UserRepository.class);
        jwtService = mock(JwtService.class);
        service = new OidcLoginCodeService(loginCodes, users, jwtService, new ObjectMapper());
    }

    @Test
    void issue_persistsOnlyHashAndBinding_withSixtySecondTtl() throws Exception {
        String code = service.issue(userId, "tenant_default", List.of("editor"));

        assertThat(code).hasSize(43).matches("[A-Za-z0-9_-]+");
        ArgumentCaptor<OidcLoginCodeEntity> saved = ArgumentCaptor.forClass(OidcLoginCodeEntity.class);
        verify(loginCodes).save(saved.capture());
        OidcLoginCodeEntity entity = saved.getValue();
        assertThat(entity.getCodeHash()).isEqualTo(hash(code)).hasSize(64);
        assertThat(entity.getCodeHash()).doesNotContain(code);
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getTenantId()).isEqualTo("tenant_default");
        assertThat(entity.getRolesJson()).isEqualTo("[\"editor\"]");
        assertThat(Duration.between(entity.getCreatedAt(), entity.getExpiresAt()))
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(entity.getConsumedAt()).isNull();
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyList());
    }

    @Test
    void exchange_success_consumesOnceAndIssuesWithBoundIdentityAndRoles() {
        OidcLoginCodeEntity consumed = consumedCode();
        when(loginCodes.consume(hash("valid-code"))).thenReturn(1);
        when(loginCodes.findByCodeHash(hash("valid-code"))).thenReturn(Optional.of(consumed));
        when(users.findByIdAndTenantId(userId, "tenant_default")).thenReturn(Optional.of(activeUser()));
        when(jwtService.issueAccessToken(userId, "alice", "tenant_default", List.of("editor")))
                .thenReturn("LOCAL.jwt");
        when(jwtService.getAccessTtl()).thenReturn(Duration.ofMinutes(15));

        OidcLoginCodeService.ExchangeResult result = service.exchange("valid-code");

        assertThat(result.accessToken()).isEqualTo("LOCAL.jwt");
        assertThat(result.expiresIn()).isEqualTo(900);
        assertThat(result.roles()).containsExactly("editor");
        assertThat(result.user().getTenantId()).isEqualTo("tenant_default");
        verify(loginCodes).consume(hash("valid-code"));
        verify(jwtService).issueAccessToken(userId, "alice", "tenant_default", List.of("editor"));
    }

    @Test
    void exchange_expiredOrConcurrentlyLostCode_returns401WithoutJwt() {
        when(loginCodes.consume(hash("expired-code"))).thenReturn(0);

        assertUnauthorized("expired-code");

        verify(loginCodes, never()).findByCodeHash(anyString());
        verify(users, never()).findByIdAndTenantId(any(), any());
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyList());
    }

    @Test
    void exchange_replay_returns401WithoutSecondJwt() {
        when(loginCodes.consume(hash("replayed-code"))).thenReturn(0);

        assertUnauthorized("replayed-code");

        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyList());
    }

    @Test
    void exchange_tamperedCode_returns401WithoutJwt() {
        when(loginCodes.consume(hash("tampered-code"))).thenReturn(0);

        assertUnauthorized("tampered-code");

        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyList());
    }

    @Test
    void exchange_missingBinding_returns401WithoutJwt() {
        OidcLoginCodeEntity consumed = consumedCode();
        when(loginCodes.consume(hash("valid-code"))).thenReturn(1);
        when(loginCodes.findByCodeHash(hash("valid-code"))).thenReturn(Optional.of(consumed));
        when(users.findByIdAndTenantId(userId, "tenant_default")).thenReturn(Optional.empty());

        assertUnauthorized("valid-code");

        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyList());
    }

    @Test
    void exchange_userDisabledAfterCodeIssue_consumesCodeButDoesNotIssueJwt() {
        OidcLoginCodeEntity consumed = consumedCode();
        UserEntity disabled = activeUser();
        disabled.setEnabled(false);
        when(loginCodes.consume(hash("valid-code"))).thenReturn(1);
        when(loginCodes.findByCodeHash(hash("valid-code"))).thenReturn(Optional.of(consumed));
        when(users.findByIdAndTenantId(userId, "tenant_default")).thenReturn(Optional.of(disabled));

        assertUnauthorized("valid-code");

        verify(loginCodes).consume(hash("valid-code"));
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyList());
    }

    @Test
    void exchange_tamperedRoleBinding_returns401WithoutJwt() {
        OidcLoginCodeEntity consumed = consumedCode();
        consumed.setRolesJson("[\"ADMIN\",42]");
        when(loginCodes.consume(hash("valid-code"))).thenReturn(1);
        when(loginCodes.findByCodeHash(hash("valid-code"))).thenReturn(Optional.of(consumed));

        assertUnauthorized("valid-code");

        verify(users, never()).findByIdAndTenantId(any(), any());
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyList());
    }

    private OidcLoginCodeEntity consumedCode() {
        OidcLoginCodeEntity entity = new OidcLoginCodeEntity();
        entity.setId(UUID.randomUUID());
        entity.setCodeHash(hash("valid-code"));
        entity.setUserId(userId);
        entity.setTenantId("tenant_default");
        entity.setRolesJson("[\"editor\"]");
        entity.setCreatedAt(Instant.now().minusSeconds(1));
        entity.setExpiresAt(Instant.now().plusSeconds(59));
        entity.setConsumedAt(Instant.now());
        return entity;
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername("alice");
        user.setDisplayName("Alice");
        user.setTenantId("tenant_default");
        user.setEnabled(true);
        user.setStatus("ACTIVE");
        return user;
    }

    private void assertUnauthorized(String code) {
        assertThatThrownBy(() -> service.exchange(code))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private static String hash(String code) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
