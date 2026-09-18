package com.nocobase.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiKeyServiceTest {

    private ApiKeyRepository repo;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        repo = mock(ApiKeyRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ApiKeyService(repo);
    }

    @Test
    void create_returnsRawKeyAndPersists() {
        ApiKeyService.CreatedKey created = service.create(
                "test-key", "read:posts", null, "tenant1", UUID.randomUUID());
        assertThat(created.rawKey()).startsWith("ncb_");
        assertThat(created.rawKey().length()).isEqualTo(4 + 32); // ncb_ + 32 hex
        assertThat(created.entity().getKeyPrefix()).isEqualTo(created.rawKey().substring(0, 8));
        assertThat(created.entity().getKeyHash()).hasSize(64);
        assertThat(created.entity().getKeyHash()).isNotEqualTo(created.rawKey());
    }

    @Test
    void validate_unknownHash_returnsEmpty() {
        when(repo.findByKeyHash(anyString())).thenReturn(Optional.empty());
        when(repo.findByKeyPrefixAndTenantIdAndRevokedAtIsNull(anyString(), anyString()))
                .thenReturn(List.of());
        // 用任意格式正确的 key
        Optional<ApiKeyEntity> result = service.validate("ncb_" + "0".repeat(32), "t1");
        assertThat(result).isEmpty();
    }

    @Test
    void validate_validKey_returnsEntityAndUpdatesLastUsed() {
        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(UUID.randomUUID());
        e.setKeyPrefix("ncb_test");
        e.setKeyHash(ApiKeyService.sha256Hex("ncb_" + "a".repeat(32)));
        e.setTenantId("t1");
        e.setCreatedAt(Instant.now());
        String rawKey = "ncb_" + "a".repeat(32);
        when(repo.findByKeyHash(ApiKeyService.sha256Hex(rawKey))).thenReturn(Optional.of(e));

        Optional<ApiKeyEntity> result = service.validate(rawKey, "t1");
        assertThat(result).isPresent();
        assertThat(result.get().getLastUsedAt()).isNotNull();
    }

    @Test
    void validate_revokedKey_returnsEmpty() {
        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(UUID.randomUUID());
        e.setKeyPrefix("ncb_test");
        e.setKeyHash(ApiKeyService.sha256Hex("ncb_" + "b".repeat(32)));
        e.setTenantId("t1");
        e.setCreatedAt(Instant.now());
        e.setRevokedAt(Instant.now());
        String rawKey = "ncb_" + "b".repeat(32);
        when(repo.findByKeyHash(ApiKeyService.sha256Hex(rawKey))).thenReturn(Optional.of(e));

        Optional<ApiKeyEntity> result = service.validate(rawKey, "t1");
        assertThat(result).isEmpty();
    }

    @Test
    void validate_wrongTenant_returnsEmpty() {
        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(UUID.randomUUID());
        e.setKeyPrefix("ncb_test");
        e.setKeyHash(ApiKeyService.sha256Hex("ncb_" + "c".repeat(32)));
        e.setTenantId("other");
        e.setCreatedAt(Instant.now());
        String rawKey = "ncb_" + "c".repeat(32);
        when(repo.findByKeyHash(ApiKeyService.sha256Hex(rawKey))).thenReturn(Optional.of(e));

        Optional<ApiKeyEntity> result = service.validate(rawKey, "t1");
        assertThat(result).isEmpty();
    }

    @Test
    void validate_expiredKey_returnsEmpty() {
        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(UUID.randomUUID());
        e.setKeyPrefix("ncb_test");
        e.setKeyHash(ApiKeyService.sha256Hex("ncb_" + "d".repeat(32)));
        e.setTenantId("t1");
        e.setCreatedAt(Instant.now());
        e.setExpiresAt(Instant.now().minusSeconds(60));
        String rawKey = "ncb_" + "d".repeat(32);
        when(repo.findByKeyHash(ApiKeyService.sha256Hex(rawKey))).thenReturn(Optional.of(e));

        Optional<ApiKeyEntity> result = service.validate(rawKey, "t1");
        assertThat(result).isEmpty();
    }

    @Test
    void validate_wrongFormat_returnsEmpty() {
        Optional<ApiKeyEntity> result = service.validate("bad", "t1");
        assertThat(result).isEmpty();
        result = service.validate("ncb_short", "t1");
        assertThat(result).isEmpty();
    }

    @Test
    void revoke_existingKey_returnsTrue() {
        UUID id = UUID.randomUUID();
        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(id);
        e.setTenantId("t1");
        e.setCreatedAt(Instant.now());
        when(repo.findById(id)).thenReturn(Optional.of(e));
        assertThat(service.revoke(id, "t1")).isTrue();
        assertThat(e.getRevokedAt()).isNotNull();
    }

    @Test
    void revoke_alreadyRevoked_returnsFalse() {
        UUID id = UUID.randomUUID();
        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(id);
        e.setTenantId("t1");
        e.setCreatedAt(Instant.now());
        e.setRevokedAt(Instant.now());
        when(repo.findById(id)).thenReturn(Optional.of(e));
        assertThat(service.revoke(id, "t1")).isFalse();
    }

    @Test
    void revoke_wrongTenant_returnsFalse() {
        UUID id = UUID.randomUUID();
        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(id);
        e.setTenantId("other");
        e.setCreatedAt(Instant.now());
        when(repo.findById(id)).thenReturn(Optional.of(e));
        assertThat(service.revoke(id, "t1")).isFalse();
    }

    @Test
    void listActive_returnsTenantKeys() {
        when(repo.findByTenantIdAndRevokedAtIsNullOrderByCreatedAtDesc("t1"))
                .thenReturn(List.of());
        assertThat(service.listActive("t1")).isEmpty();
    }

    @Test
    void sha256Hex_isDeterministic() {
        assertThat(ApiKeyService.sha256Hex("test"))
                .isEqualTo(ApiKeyService.sha256Hex("test"));
        assertThat(ApiKeyService.sha256Hex("test"))
                .isNotEqualTo(ApiKeyService.sha256Hex("test2"));
    }

    @Test
    void randomHex_returnsRequestedLength() {
        assertThat(ApiKeyService.randomHex(32).length()).isEqualTo(32);
        assertThat(ApiKeyService.randomHex(16).length()).isEqualTo(16);
    }

    @Test
    void hasScope_returnsTrueWhenScopePresent() {
        // 模拟 SecurityContext 中有 SCOPE_read:posts
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "principal", "creds",
                java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_API"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_read:posts"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_write:posts")
                )
        );
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            assertThat(service.hasScope("read:posts")).isTrue();
            assertThat(service.hasScope("write:posts")).isTrue();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void hasScope_returnsFalseWhenScopeMissing() {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "principal", "creds",
                java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_API"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_read:posts")
                )
        );
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            assertThat(service.hasScope("write:posts")).isFalse();
            assertThat(service.hasScope("admin")).isFalse();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void hasScope_returnsTrueWhenNoAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        try {
            // 无认证上下文时,hasScope 返回 false(而非抛异常)
            assertThat(service.hasScope("read:posts")).isFalse();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
