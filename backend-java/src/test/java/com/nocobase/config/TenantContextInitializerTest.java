package com.nocobase.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nocobase.tenant.SchemaTenantConnectionProvider;
import com.nocobase.tenant.TenantIdentifierResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantContextInitializerTest {

    private SchemaTenantConnectionProvider provider;
    private TenantIdentifierResolver resolver;

    @BeforeEach
    void setUp() {
        provider = mock(SchemaTenantConnectionProvider.class);
        resolver = mock(TenantIdentifierResolver.class);
    }

    @Test
    void afterSingletonsInstantiated_setsBridge() {
        TenantContextInitializer initializer = new TenantContextInitializer(provider, resolver);
        initializer.afterSingletonsInstantiated();

        // Bridge 应指向同一实例
        assertThat(TenantContextBridge.getProvider()).isSameAs(provider);
        assertThat(TenantContextBridge.getResolver()).isSameAs(resolver);
    }

    @Test
    void bridgeHoldsLatestProvider() {
        // 二次注入应覆盖
        SchemaTenantConnectionProvider old = mock(SchemaTenantConnectionProvider.class);
        new TenantContextInitializer(old, resolver).afterSingletonsInstantiated();
        new TenantContextInitializer(provider, resolver).afterSingletonsInstantiated();

        assertThat(TenantContextBridge.getProvider()).isSameAs(provider);
    }
}
