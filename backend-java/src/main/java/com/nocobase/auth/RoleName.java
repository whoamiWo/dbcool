package com.nocobase.auth;

/**
 * 角色名称枚举 (Wave3 B7 升级: 字符串常量 → 枚举类型)。
 *
 * <p>枚举 name() 即为角色标识,Spring Security 自动添加 "ROLE_" 前缀。
 * 在 SpEL 中使用: {@code @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN.name())")}
 *
 * <p>向后兼容: 保留 {@link #ADMIN}/{@link #USER}/{@link #ANONYMOUS} 三个
 * {@code String} 静态常量,供遗留代码(以及 SpEL 中不便调用 .name() 的场景)使用。
 */
public enum RoleName {
    /** 系统管理员 —— 全权限。 */
    ADMIN,
    /** 普通用户 —— 仅能读写自己的数据。 */
    USER,
    /** 匿名/游客 —— 仅允许登录页与部分公开 API。 */
    ANONYMOUS;

    // ---- 向后兼容常量(旧代码直接引用 RoleName.ADMIN 等) ----
    public static final String ADMIN_NAME = ADMIN.name();
    public static final String USER_NAME = USER.name();
    public static final String ANONYMOUS_NAME = ANONYMOUS.name();

    private RoleName() {}
}
