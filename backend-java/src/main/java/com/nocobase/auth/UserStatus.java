package com.nocobase.auth;

/**
 * 人员状态常量（V77 <code>users.status</code>）。
 *
 * <p>与 {@code users.enabled} 的分工：
 * <ul>
 *   <li>{@code enabled}：账号是否可登录（运维开关，历史字段）</li>
 *   <li>{@code status}：人员雇佣状态（业务语义）。离职/停用会同时把 {@code enabled} 置 false。</li>
 * </ul>
 */
public final class UserStatus {

    /** 在职。 */
    public static final String ACTIVE = "ACTIVE";

    /** 离职（外部平台同步时本轮未出现）。 */
    public static final String RESIGNED = "RESIGNED";

    /** 停用（管理员手动）。 */
    public static final String DISABLED = "DISABLED";

    private UserStatus() {}
}
