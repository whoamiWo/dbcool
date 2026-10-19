package com.nocobase.auth;

/**
 * 角色名称常量 (PHASE115 审计项 #10: 移除硬编码字符串).
 *
 * <p>Spring Security 表达式中使用: {@code @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")}
 * 注意: Spring Security 会自动添加 "ROLE_" 前缀, 所以这里只写 "ADMIN".
 */
public final class RoleName {
    /** 管理员角色。 */
    public static final String ADMIN = "ADMIN";

    private RoleName() {
        // 工具类禁止实例化
    }
}
