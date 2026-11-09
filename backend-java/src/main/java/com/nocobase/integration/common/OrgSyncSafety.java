package com.nocobase.integration.common;

/**
 * 组织同步离职安全策略 — 飞书与钉钉共用（G1/G3）。
 *
 * <p>两个平台的同步流程同构：先同步部门与成员，再对「本轮未出现」的托管账号判离职。
 * 判离职必须同时满足：
 * <ol>
 *   <li><b>完整性</b>：部门全部 upsert 成功、成员拉取零失败、零错误；</li>
 *   <li><b>比例熔断</b>：待离职人数不得超过 {@code max(1, round(seen * 20%))} 且不得超过
 *       {@link #MAX_AUTO_RESIGN}，避免一次 429/超时把整个部门误判为离职；</li>
 *   <li><b>dryRun</b>：只返回名单，不写库、不吊销会话；</li>
 *   <li><b>force</b>：计划内大裁员由 ADMIN 显式放行（调用方负责审计）。</li>
 * </ol>
 *
 * <p>抽成共享类是为了防止两个服务各写一套阈值后再次漂移。
 */
public final class OrgSyncSafety {

    /** 单次自动离职的硬上限；超过需 {@code force=true} 人工放行。 */
    public static final int MAX_AUTO_RESIGN = 10;

    /** 自动离职比例上限（相对本轮已见用户数）。 */
    public static final double RESIGN_RATIO = 0.20;

    private OrgSyncSafety() {}

    /**
     * 本轮同步是否完整。
     *
     * <p>任一部门/用户失败都必须为 {@code false}：失败部门的成员不会进入 {@code seen}，
     * 此时判离职就是批量误伤。
     */
    public static boolean isComplete(int usersFailed, int errorCount,
                                     int departmentsSynced, int departmentsTotal) {
        return usersFailed == 0
                && errorCount == 0
                && departmentsSynced == departmentsTotal;
    }

    /**
     * 比例阈值，下限固定为 1。
     *
     * <p>不加下限时 {@code seen=4, candidates=1} 会因 {@code 1 > 4*0.2} 被熔断，
     * 小组织永远无法记录真实离职；下限 1 让单次离职可以正常执行。
     */
    public static int resignRatioThreshold(int seen) {
        return Math.max(1, (int) Math.round(seen * RESIGN_RATIO));
    }

    /** 离职写入决策。 */
    public enum Decision {
        /** 没有待离职用户。 */
        NONE,
        /** dryRun：只返回名单。 */
        DRY_RUN,
        /** 在阈值内，正常写入。 */
        ALLOW,
        /** 显式 force，跳过熔断。 */
        FORCED,
        /** 超过阈值，拒绝写入。 */
        TRIPPED
    }

    /**
     * 判定本次离职写入方式。
     *
     * @param candidates 待离职人数
     * @param seen       本轮已见用户数
     * @param dryRun     是否只返回名单
     * @param force      是否由管理员显式放行
     */
    public static Decision evaluate(int candidates, int seen, boolean dryRun, boolean force) {
        if (candidates <= 0) return Decision.NONE;
        if (dryRun) return Decision.DRY_RUN;
        if (force) return Decision.FORCED;
        // seen=0 说明本轮一个用户都没拉到，任何「未出现」都不足为凭。
        if (seen <= 0) return Decision.TRIPPED;
        if (candidates > MAX_AUTO_RESIGN) return Decision.TRIPPED;
        if (candidates > resignRatioThreshold(seen)) return Decision.TRIPPED;
        return Decision.ALLOW;
    }

    /** 熔断原因文案（响应与日志共用）。 */
    public static String breakerReason(int candidates, int seen) {
        return "待离职 " + candidates + " 人 / 已见 " + seen + " 人，超过安全阈值（上限 "
                + MAX_AUTO_RESIGN + " 人且不超过已见用户的 "
                + (int) Math.round(RESIGN_RATIO * 100) + "%），已拒绝写入";
    }
}
