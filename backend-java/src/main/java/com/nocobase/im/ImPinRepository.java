package com.nocobase.im;

import com.nocobase.im.entity.ImPinEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 置顶消息仓库。
 */
@Repository
public interface ImPinRepository extends JpaRepository<ImPinEntity, UUID> {

    /** 某频道所有有效置顶(按置顶时间倒序)。 */
    @Query("select p from ImPinEntity p "
            + "where p.tenantId = :tenantId and p.channelId = :channelId "
            + "and p.unpinnedAt is null "
            + "order by p.pinnedAt desc")
    List<ImPinEntity> findActivePins(@Param("tenantId") String tenantId,
                                     @Param("channelId") UUID channelId);

    /** 某频道某消息的置顶记录(用于判断是否已置顶)。 */
    Optional<ImPinEntity> findByTenantIdAndChannelIdAndMessageIdAndUnpinnedAtIsNull(
            String tenantId, UUID channelId, UUID messageId);

    /** 软删除置顶(置 unpinnedAt),保留历史。 */
    @Query("update ImPinEntity p set p.unpinnedAt = :now "
            + "where p.tenantId = :tenantId and p.channelId = :channelId "
            + "and p.messageId = :messageId and p.unpinnedAt is null")
    int softUnpin(@Param("tenantId") String tenantId,
                  @Param("channelId") UUID channelId,
                  @Param("messageId") UUID messageId,
                  @Param("now") java.time.Instant now);
}