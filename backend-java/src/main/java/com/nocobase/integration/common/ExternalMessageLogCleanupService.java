package com.nocobase.integration.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExternalMessageLogCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ExternalMessageLogCleanupService.class);

    private final ExternalMessageLogRepository repository;

    public ExternalMessageLogCleanupService(ExternalMessageLogRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 3 * * * ?")
    public void cleanupOldEntries() {
        int retentionDays = 30;
        long deletedCount = repository.deleteByProcessedAtBefore(
                java.time.Instant.now().minus(java.time.Duration.ofDays(retentionDays)));
        log.info("[ExternalMessageLog] 清理 {} 天前的记录，删除 {} 条", retentionDays, deletedCount);
    }
}
