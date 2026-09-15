package com.nocobase.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantIdentifierResolverTest {

    private final TenantIdentifierResolver resolver = new TenantIdentifierResolver();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void resolveCurrentTenantIdentifier_defaultTenant_returnsPublic() {
        TenantContext.clear();
        assertThat(resolver.resolveCurrentTenantIdentifier())
                .isEqualTo(TenantContext.PUBLIC_SCHEMA);
    }

    @Test
    void resolveCurrentTenantIdentifier_setTenant_returnsTenantIdAsSchema() {
        TenantContext.set("acme_corp");
        try {
            assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo("acme_corp");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void resolveCurrentTenantIdentifier_defaultSchemaMapping() {
        // 默认租户 "tenant_default" 映射 public(零迁移策略)
        TenantContext.set(TenantContext.DEFAULT_TENANT);
        try {
            assertThat(resolver.resolveCurrentTenantIdentifier())
                    .isEqualTo(TenantContext.PUBLIC_SCHEMA);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void validateExistingCurrentSessions_isTrue() {
        assertThat(resolver.validateExistingCurrentSessions()).isTrue();
    }
}
