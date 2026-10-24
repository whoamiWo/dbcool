package com.nocobase.tenant;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantRepository extends JpaRepository<TenantEntity, String> {
    List<TenantEntity> findByStatus(TenantEntity.Status status);

    /**
     * 原子地消耗一次 API 配额：仅当 usage < limit 时才 +1。
     *
     * <p>返回**受影响行数**：1 = 消耗成功，0 = 已达上限（超限）。
     *
     * <p>为什么必须用原子 UPDATE 而不是 read-modify-write：
     * 原实现是「findById → 判断 → setUsage(usage+1) → save」，
     * 再用 {@code synchronized(tenant)} 保护 —— 但每次 findById 拿到的都是
     * <b>不同实例</b>，高并发下这个锁形同虚设，多个线程互相覆盖写入，
     * 计数严重丢失。实测：k6 压 28647 个请求，usage 只累计到一千出头，
     * 永远够不到 limit，配额因此从不触发（PHASE92 实测 quota_429 = 0）。
     * 原子 UPDATE 把这四步压成一条 SQL，天然并发安全（多副本也安全）。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "UPDATE TenantEntity t SET t.apiCallUsage = t.apiCallUsage + 1 "
                    + "WHERE t.id = :id AND t.apiCallUsage < t.apiCallLimit")
    int consumeApiQuota(java.lang.String id);

    /**
     * PHASE135：移除「全表清零」方法 resetAllApiUsage()。
     *
     * <p>原方法每分钟执行 {@code UPDATE tenant SET api_call_usage = 0}，
     * 目的是配合「apiCallUsage 表示每分钟用量」的语义 —— 若只增不减，
     * 租户用完配额后会被永久封禁（PHASE92 实测：usage 停在 3600，后续全部 429）。
     *
     * <p><b>移除依据</b>：配额权威源已迁移到 Redis，窗口由分钟桶 key
     * （{@code {minuteBucket}:quota:api:{tenantId}}，TTL 3 分钟）自然滚动，
     * 每分钟自动进入新桶，不存在「只增不减」风险，故不再需要数据库侧清零。
     * 该全表 UPDATE 是压测期间 tenant 行锁的争抢源之一（50 VU P95=487ms）。
     *
     * <p>数据库 api_call_usage 现为「累计调用快照（最终一致，延迟 ≤60 秒）」，
     * 仅供审计；需要归零单个租户时使用 TenantQuotaService.resetApiUsage(tenantId)。
     */

    /**
     * 批量增加租户 API 用量（PHASE131 P95 优化 / PHASE135 保留）
     *
     * <p>用一条 UPDATE 完成 N 个租户的 usage 累加，避免逐租户 SELECT+UPDATE
     * 引发的 N+1 查询与行锁串行化。由 TenantQuotaService.flushPendingIncrementsToDB()
     * 每 60 秒调用一次（PHASE135 由 5 秒降频而来）。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "UPDATE TenantEntity t SET t.apiCallUsage = t.apiCallUsage + ?2 WHERE t.id = ?1")
    int incrementApiUsage(String tenantId, long delta);
}
