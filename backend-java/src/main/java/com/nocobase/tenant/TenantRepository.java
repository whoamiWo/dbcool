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
     * 将所有租户的 API 用量清零（每分钟一次，配合 apiCallLimit 的"每分钟"语义）。
     *
     * <p>必须有这条：配额是**按分钟**的，若计数只增不减，租户用完配额后
     * 会被**永久封禁**（PHASE92 实测：压测后 usage 停在 3600，后续请求全部 429）。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE TenantEntity t SET t.apiCallUsage = 0")
    int resetAllApiUsage();
}
