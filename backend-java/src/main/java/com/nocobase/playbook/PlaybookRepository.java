package com.nocobase.playbook;

import com.nocobase.playbook.PlaybookEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Playbook 剧本仓库。
 */
@Repository
public interface PlaybookRepository extends JpaRepository<PlaybookEntity, UUID> {

    /** 某租户下所有剧本(按创建时间倒序)。 */
    @Query("select p from PlaybookEntity p where p.tenantId = :tenantId "
            + "order by p.createdAt desc")
    List<PlaybookEntity> findByTenantId(@Param("tenantId") String tenantId);

    /** 某频道下已激活的剧本。 */
    @Query("select p from PlaybookEntity p where p.tenantId = :tenantId "
            + "and p.channelId = :channelId and p.status = 'ACTIVE' "
            + "order by p.createdAt desc")
    List<PlaybookEntity> findActiveByChannel(@Param("tenantId") String tenantId,
                                            @Param("channelId") UUID channelId);

    /** 某租户下指定状态的剧本(status 为 null 时返回全部)。 */
    default List<PlaybookEntity> findByTenantIdAndStatus(String tenantId, String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return findByTenantId(tenantId);
        }
        return findByTenantIdAndStatusInternal(tenantId,
                PlaybookEntity.Status.valueOf(status.toUpperCase()));
    }

    /** 某租户下指定状态的剧本(内部实现)。 */
    @Query("select p from PlaybookEntity p where p.tenantId = :tenantId "
            + "and p.status = :status order by p.createdAt desc")
    List<PlaybookEntity> findByTenantIdAndStatusInternal(@Param("tenantId") String tenantId,
                                                         @Param("status") PlaybookEntity.Status status);
}