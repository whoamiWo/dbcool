package com.nocobase.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class OidcLoginCodeRepositoryTest {

    @Autowired OidcLoginCodeRepository repository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("CREATE DOMAIN IF NOT EXISTS JSONB AS CLOB");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS oidc_login_code (
                    id UUID PRIMARY KEY,
                    code_hash VARCHAR(64) NOT NULL UNIQUE,
                    user_id UUID NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    roles_json JSONB NOT NULL DEFAULT '[]',
                    created_at TIMESTAMP NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    consumed_at TIMESTAMP
                )
                """);
        repository.deleteAll();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void consume_isSingleUseAndRejectsExpiredCodes() {
        OidcLoginCodeEntity valid = code("a".repeat(64), Instant.now().plusSeconds(86400));
        OidcLoginCodeEntity expired = code("b".repeat(64), Instant.now().minusSeconds(86400));
        repository.saveAndFlush(valid);
        repository.saveAndFlush(expired);

        assertThat(repository.consume(valid.getCodeHash())).isEqualTo(1);
        assertThat(repository.consume(valid.getCodeHash())).isZero();
        assertThat(repository.consume(expired.getCodeHash())).isZero();
        assertThat(repository.consume("f".repeat(64))).isZero();
        assertThat(repository.findByCodeHash(valid.getCodeHash())).get()
                .extracting(OidcLoginCodeEntity::getConsumedAt).isNotNull();
        assertThat(repository.findByCodeHash(expired.getCodeHash())).get()
                .extracting(OidcLoginCodeEntity::getConsumedAt).isNull();
    }

    private OidcLoginCodeEntity code(String hash, Instant expiresAt) {
        OidcLoginCodeEntity entity = new OidcLoginCodeEntity();
        entity.setId(UUID.randomUUID());
        entity.setCodeHash(hash);
        entity.setUserId(UUID.randomUUID());
        entity.setTenantId("tenant_default");
        entity.setRolesJson("[\"editor\"]");
        entity.setCreatedAt(Instant.now());
        entity.setExpiresAt(expiresAt);
        return entity;
    }
}
