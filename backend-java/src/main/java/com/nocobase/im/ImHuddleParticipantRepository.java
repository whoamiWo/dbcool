package com.nocobase.im;

import com.nocobase.im.entity.ImHuddleParticipantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImHuddleParticipantRepository extends JpaRepository<ImHuddleParticipantEntity, UUID> {
    
    boolean existsByHuddleIdAndUserId(UUID huddleId, UUID userId);
    
    Optional<ImHuddleParticipantEntity> findByHuddleIdAndUserId(UUID huddleId, UUID userId);
    
    List<ImHuddleParticipantEntity> findByHuddleId(UUID huddleId);

    List<ImHuddleParticipantEntity> findByHuddleIdAndLeftAtIsNull(UUID huddleId);
}
