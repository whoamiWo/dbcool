package com.nocobase.im;

import com.nocobase.im.entity.ImMessageReactionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImReactionRepository extends JpaRepository<ImMessageReactionEntity, UUID> {

    List<ImMessageReactionEntity> findByMessageId(UUID messageId);

    /** 幂等:同一用户对同一消息的同一 emoji 只记录一次。 */
    Optional<ImMessageReactionEntity> findByMessageIdAndUserIdAndEmoji(
            UUID messageId, UUID userId, String emoji);

    long countByMessageIdAndEmoji(UUID messageId, String emoji);
}
