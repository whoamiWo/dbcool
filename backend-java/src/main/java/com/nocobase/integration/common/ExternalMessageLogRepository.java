package com.nocobase.integration.common;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExternalMessageLogRepository extends JpaRepository<ExternalMessageLogEntity, UUID> {

    /**
     * 检查是否存在已处理的外部消息。
     */
    Optional<ExternalMessageLogEntity> findBySourceAndExternalMessageId(String source, String externalMessageId);
}
