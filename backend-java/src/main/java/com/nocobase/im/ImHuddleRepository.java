package com.nocobase.im;

import com.nocobase.im.entity.ImHuddleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ImHuddleRepository extends JpaRepository<ImHuddleEntity, UUID> {
    
    List<ImHuddleEntity> findByTenantIdAndChannelIdAndStatusNot(
            String tenantId, UUID channelId, String statusNot);
    
    @Query("SELECT h FROM ImHuddleEntity h WHERE h.tenantId = :tenantId AND h.id = :id")
    java.util.Optional<ImHuddleEntity> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") String tenantId);
}
