package com.nocobase.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.config.AsyncTask;
import com.nocobase.config.AsyncTaskHandler;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PHASE 55 Stage 2 — 迁移任务消费器。
 *
 * <p>消费 {@code meta.migration} 类型的异步任务,
 * 执行 DDL 变更,失败抛异常 → RabbitMQ 死信机制进重试队列。
 */
@Component
public class MigrationTaskHandler implements AsyncTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(MigrationTaskHandler.class);

    private final MigrationJobRepository jobRepository;
    private final AsyncMigrationService migrationService;
    private final ObjectMapper objectMapper;

    public MigrationTaskHandler(MigrationJobRepository jobRepository,
                                 AsyncMigrationService migrationService,
                                 ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.migrationService = migrationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(AsyncTask task) {
        Map<String, Object> payload = task.getPayload();
        String jobIdStr = (String) payload.get("jobId");
        UUID jobId = UUID.fromString(jobIdStr);

        MigrationJobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.error("[migration] job {} 不存在,拒绝放行", jobId);
            return;
        }
        job.setStatus(MigrationJobEntity.Status.RUNNING);
        job.setStartedAt(Instant.now());
        jobRepository.save(job);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> fieldPayload = (Map<String, Object>) payload.get("payload");
            // 委托给 executeSync 执行 DDL
            migrationService.executeSync(
                    job.getCollectionName(),
                    job.getOperation(),
                    fieldPayload
            );
            job.setStatus(MigrationJobEntity.Status.COMPLETED);
            job.setFinishedAt(Instant.now());
            jobRepository.save(job);
            log.info("[migration] job {} 完成", jobId);
        } catch (Exception e) {
            log.error("[migration] job {} 失败: {}", jobId, e.getMessage(), e);
            job.setStatus(MigrationJobEntity.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setFinishedAt(Instant.now());
            jobRepository.save(job);
            // 抛异常 → RabbitMQ 重试
            throw new RuntimeException("迁移任务失败,转重试: " + jobId, e);
        }
    }

    @Override
    public java.util.List<String> supportedTypes() {
        return java.util.List.of(AsyncMigrationService.TASK_TYPE);
    }
}
