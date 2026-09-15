package com.nocobase.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Hibernate 多租户 schema 解析器(Week 41 D6 Step G2 — ADR-007).
 *
 * <p>把 {@link TenantContext#currentSchema()} 暴露给 Hibernate,
 * 使其在取连接时通过 {@code SET search_path} 切到对应 schema。
 *
 * <p><strong>零迁移</strong>:默认租户映射到 {@code public},存量数据无需迁移,
 * 行为与 Week 1-40 完全一致。
 *
 * @see SchemaTenantConnectionProvider
 * @see ADR/007-multitenancy.md
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver {

    @Override
    public String resolveCurrentTenantIdentifier() {
        return TenantContext.currentSchema();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
