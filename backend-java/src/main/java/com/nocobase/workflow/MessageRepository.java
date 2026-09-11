package com.nocobase.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {
    List<MessageEntity> findByRecipientOrderByCreatedAtDesc(UUID recipient);

    /** cursor 分页 — 拿 created_at < before 的 limit 条 */
    List<MessageEntity> findByRecipientAndCreatedAtLessThanOrderByCreatedAtDesc(
            UUID recipient, Instant before, Pageable pageable);

    List<MessageEntity> findByRecipientAndCreatedAtLessThanAndReadOrderByCreatedAtDesc(
            UUID recipient, Instant before, boolean read, Pageable pageable);

    List<MessageEntity> findByRecipientAndReadOrderByCreatedAtDesc(UUID recipient, boolean read);
    long countByRecipientAndRead(UUID recipient, boolean read);
}
