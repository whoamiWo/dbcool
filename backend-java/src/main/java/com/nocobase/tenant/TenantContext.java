package com.nocobase.tenant;

/**
 * 多租户上下文(Week 41 D6 Step G1 — ADR-007 实现基线;Step G2 扩展 schema 路由).
 *
 * <p>ThreadLocal 持有当前请求的 tenantId,供 service / repository 取用。
 *
 * <p>Step G1 实现:
 * <ul>
 *   <li>ThreadLocal 持有 tenantId</li>
 *   <li>JWT 解析时设置,JWT 过滤器结束时清理</li>
 *   <li>为清理硬编码 `tenant_default` 提供统一来源</li>
 * </ul>
 *
 * <p>Step G2 扩展(Week 41 复核):
 * <ul>
 *   <li>{@link #currentSchema()} 提供 schema 映射,供 Hibernate 多租户与动态表使用</li>
 *   <li>采用<strong>零迁移策略</strong>:默认租户映射到 `public`,存量数据无需迁移</li>
 * </ul>
 *
 * <p>向后兼容:未设置时返回 `"tenant_default"`,与 Week 1-40 单租户模式兼容。
 *
 * @see ADR/007-multitenancy.md
 */
public final class TenantContext {

    /** 默认租户(Week 1-40 单租户模式 + 迁移期兼容)。Week 42+ 可收紧为空抛错。 */
    public static final String DEFAULT_TENANT = "tenant_default";

    /** public schema —— 存量(Week 1-40)数据所在,默认租户映射到此处。 */
    public static final String PUBLIC_SCHEMA = "public";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    /** 当前线程的 tenantId。未设置时返默认租户(兼容现有单租户代码)。 */
    public static String currentTenantId() {
        String t = CURRENT.get();
        return t != null ? t : DEFAULT_TENANT;
    }

    /**
     * 当前线程的 tenantId,要求已设置(用于 JWT 已过滤的请求路径)。
     *
     * <p>JwtAuthFilter 在解析后立即调 {@link #set(String)},后续请求路径中
     * 任何 service / repository 调此方法都应拿到非默认值。
     */
    public static String requireTenantId() {
        String t = CURRENT.get();
        if (t == null || t.isBlank()) {
            throw new IllegalStateException(
                    "TenantContext 未设置 — JWT 过滤器必须先调 set()。检查请求是否经过 JwtAuthFilter。");
        }
        return t;
    }

    /**
     * 当前线程对应的数据库 schema 名(Week 41 D6 Step G2 — ADR-007 Schema 隔离)。
     *
     * <p>映射规则(<strong>零迁移策略</strong>,经评估风险最低):
     * <ul>
     *   <li>{@code tenant_default} → {@code public}:存量表本来就在 public schema,
     *       行为与 Week 1-40 完全一致,**不需要任何数据迁移**(C-R01)</li>
     *   <li>其他租户 → 其 schemaName(当前实现默认等于 tenantId),
     *       由租户创建流程负责初始化该 schema</li>
     * </ul>
     */
    public static String currentSchema() {
        String tenantId = currentTenantId();
        return DEFAULT_TENANT.equals(tenantId) ? PUBLIC_SCHEMA : tenantId;
    }

    /**
     * 设置当前线程的 tenantId(JwtAuthFilter 在解析 JWT 后调用)。
     *
     * <p>必须在 finally 中 {@link #clear()} 防止线程复用泄漏(C-R02)。
     */
    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        CURRENT.set(tenantId);
    }

    /** 清除当前线程的 tenantId(JwtAuthFilter finally 块)。 */
    public static void clear() {
        CURRENT.remove();
    }

    /** 当前是否设置了 tenantId(用于测试与调试)。 */
    public static boolean isSet() {
        return CURRENT.get() != null;
    }
}
