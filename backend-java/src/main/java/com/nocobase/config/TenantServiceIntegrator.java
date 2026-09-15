package com.nocobase.config;

import com.nocobase.tenant.SchemaTenantConnectionProvider;
import com.nocobase.tenant.TenantIdentifierResolver;
import org.hibernate.boot.Metadata;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

/**
 * Week 41 D6 Step G2 — Hibernate Integrator 桥接 Spring Bean.
 *
 * <p>Hibernate 启动时,通过此 Integrator 把 Spring 管理的 provider/resolver
 * 注入到 SessionFactoryServiceRegistry,解决 yml 中 Class.forName 路径
 * 无法拿到 Spring DataSource 的问题。
 *
 * <p>启用方式:
 * <ol>
 *   <li>META-INF/services/org.hibernate.integrator.spi.Integrator 指向本类</li>
 *   <li>spring.jpa.properties.hibernate.multiTenancy = SCHEMA</li>
 * </ol>
 *
 * @see SchemaTenantConnectionProvider
 * @see TenantIdentifierResolver
 * @see TenantContextBridge
 * @see TenantContextInitializer
 */
public class TenantServiceIntegrator implements Integrator {

    @Override
    public void integrate(Metadata metadata, SessionFactoryImplementor sessionFactory,
                          SessionFactoryServiceRegistry serviceRegistry) {
        SchemaTenantConnectionProvider provider = TenantContextBridge.getProvider();
        TenantIdentifierResolver resolver = TenantContextBridge.getResolver();

        if (provider != null) {
            org.hibernate.service.spi.ServiceBinding<MultiTenantConnectionProvider> pBinding =
                    serviceRegistry.locateServiceBinding(MultiTenantConnectionProvider.class);
            if (pBinding != null) pBinding.setService(provider);
        }
        // CurrentTenantIdentifierResolver 不依赖 Spring 资源,无需在 Integrator 替换;
        // 由 application.yml 的 multi_tenant_identifier_resolver 类名路径自动装配。
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory,
                             SessionFactoryServiceRegistry serviceRegistry) {
        // no-op:Spring 容器关停时会清理
    }
}
