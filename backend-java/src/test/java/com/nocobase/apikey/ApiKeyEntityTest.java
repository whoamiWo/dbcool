package com.nocobase.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiKeyEntityTest {

    @Test
    void isValid_returnsTrueForFreshKey() {
        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(UUID.randomUUID());
        e.setCreatedAt(Instant.now());
        assertThat(e.isValid(Instant.now())).isTrue();
    }

    @Test
    void isValid_returnsFalseWhenRevoked() {
        ApiKeyEntity e = new ApiKeyEntity();
        e.setCreatedAt(Instant.now());
        e.setRevokedAt(Instant.now());
        assertThat(e.isValid(Instant.now())).isFalse();
    }

    @Test
    void isValid_returnsFalseWhenExpired() {
        ApiKeyEntity e = new ApiKeyEntity();
        e.setCreatedAt(Instant.now());
        e.setExpiresAt(Instant.now().minusSeconds(60));
        assertThat(e.isValid(Instant.now())).isFalse();
    }

    @Test
    void isValid_returnsTrueWhenExpiresInFuture() {
        ApiKeyEntity e = new ApiKeyEntity();
        e.setCreatedAt(Instant.now());
        e.setExpiresAt(Instant.now().plusSeconds(86400));
        assertThat(e.isValid(Instant.now())).isTrue();
    }
}
