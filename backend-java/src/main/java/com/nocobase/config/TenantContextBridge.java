package com.nocobase.config;

import com.nocobase.tenant.SchemaTenantConnectionProvider;
import com.nocobase.tenant.TenantIdentifierResolver;

/**
 * Week 41 D6 Step G2 — Spring ApplicationContext 静态桥接器.
 *
 * <p>{@link TenantServiceContributor} 在 Hibernate ServiceRegistry 启动时通过本类
 * 拿到由 Spring 管理的 provider/resolver 实例。
 *
 * <p>由 {@code TenantContextInitializer} 在 Spring ApplicationContext 就绪后
 * 调用 {@link #set} 注入。失败时返回 null,Hibernate 降级为单租户模式。
 */
public final class TenantContextBridge {

    private static volatile SchemaTenantConnectionProvider provider;
    private static volatile TenantIdentifierResolver resolver;

    private TenantContextBridge() {}

    public static void set(SchemaTenantConnectionProvider p, TenantIdentifierResolver r) {
        provider = p;
        resolver = r;
    }

    public static SchemaTenantConnectionProvider getProvider() {
        return provider;
    }

    public static TenantIdentifierResolver getResolver() {
        return resolver;
    }
}
