package com.nocobase.compliance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 用户数据删除请求台账 Repository（V52 表）。
 *
 * <p>仅由 {@code UserDataErasureService} 在删除成功路径写入；
 * 对外查询仍走既有审计日志接口（{@code GET /api/audit/logs?action=user.data.erasure}），
 * 本 Repository 不开放独立端点。
 */
@Repository
public interface UserDataErasureLogRepository extends JpaRepository<UserDataErasureLogEntity, String> {

    /** 合规审计：按租户查询该租户发起过的全部删除请求（时间倒序由 SQL 处理）。 */
    List<UserDataErasureLogEntity> findByTenantIdOrderByRequestedAtDesc(String tenantId);

    /** 合规审计：按被删除用户查询其删除请求台账。 */
    List<UserDataErasureLogEntity> findByUserIdOrderByRequestedAtDesc(String userId);
}