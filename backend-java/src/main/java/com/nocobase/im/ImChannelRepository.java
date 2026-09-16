package com.nocobase.im;

import com.nocobase.im.entity.ImChannelEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImChannelRepository extends JpaRepository<ImChannelEntity, UUID> {

    /** 我参与的、未归档的频道,按活跃度倒序。 */
    List<ImChannelEntity> findByTenantIdAndArchivedAtIsNullOrderByUpdatedAtDesc(String tenantId);

    Optional<ImChannelEntity> findByIdAndTenantId(UUID id, String tenantId);

    /** 直聊去重:同租户下相同 directKey 只允许一条。 */
    Optional<ImChannelEntity> findByTenantIdAndDirectKey(String tenantId, String directKey);
}
