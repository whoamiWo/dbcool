package com.nocobase.workflow;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {
    List<MessageEntity> findByRecipientOrderByCreatedAtDesc(UUID recipient);
    List<MessageEntity> findByRecipientAndReadOrderByCreatedAtDesc(UUID recipient, boolean read);
    long countByRecipientAndRead(UUID recipient, boolean read);
}
