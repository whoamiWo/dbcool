package com.nocobase.im;

import com.nocobase.im.entity.ImChannelMemberEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImChannelMemberRepository extends JpaRepository<ImChannelMemberEntity, UUID> {

    /** 某用户加入的所有频道(用于频道列表)。 */
    List<ImChannelMemberEntity> findByTenantIdAndUserId(String tenantId, UUID userId);

    List<ImChannelMemberEntity> findByChannelId(UUID channelId);

    Optional<ImChannelMemberEntity> findByChannelIdAndUserId(UUID channelId, UUID userId);

    boolean existsByChannelIdAndUserId(UUID channelId, UUID userId);
}
