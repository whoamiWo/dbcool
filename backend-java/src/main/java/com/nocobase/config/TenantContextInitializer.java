package com.nocobase.config;

import com.nocobase.tenant.SchemaTenantConnectionProvider;
import com.nocobase.tenant.TenantIdentifierResolver;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Week 41 D6 Step G2 — 把 Spring 管理的 provider/resolver 注入到
 * {@link TenantContextBridge} 供 Hibernate ServiceRegistry 使用。
 *
 * <p>{@link SmartInitializingSingleton} 在所有单例 Bean 初始化后调,
 * 早于 Hibernate SessionFactory 构建。
 */
@Component
public class TenantContextInitializer implements SmartInitializingSingleton {

    private final SchemaTenantConnectionProvider provider;
    private final TenantIdentifierResolver resolver;

    public TenantContextInitializer(
            SchemaTenantConnectionProvider provider,
            TenantIdentifierResolver resolver
    ) {
        this.provider = provider;
        this.resolver = resolver;
    }

    @Override
    public void afterSingletonsInstantiated() {
        TenantContextBridge.set(provider, resolver);
    }
}
