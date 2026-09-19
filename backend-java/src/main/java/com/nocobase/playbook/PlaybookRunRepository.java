package com.nocobase.playbook;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Playbook 运行实例仓库（Phase 48 F2）。
 */
@Repository
public interface PlaybookRunRepository extends JpaRepository<PlaybookRunEntity, UUID> {

    /** 某剧本的运行历史(按开始时间倒序)。 */
    @Query("select r from PlaybookRunEntity r where r.playbookId = :playbookId "
            + "order by r.startedAt desc")
    List<PlaybookRunEntity> findByPlaybookId(@Param("playbookId") UUID playbookId);

    /** SLA 到期升级:某租户下已逾期但未完成的运行。 */
    @Query("select r from PlaybookRunEntity r where r.tenantId = :tenantId "
            + "and r.status = 'RUNNING' and r.dueAt < :now")
    List<PlaybookRunEntity> findOverdue(@Param("tenantId") String tenantId,
                                        @Param("now") Instant now);
}
