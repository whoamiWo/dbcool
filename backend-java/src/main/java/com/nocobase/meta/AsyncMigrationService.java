package com.nocobase.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 异步迁移服务 — US-005 修改表(大表场景).
 *
 * <p>Week 7 MVP:同步迁移 + lock_timeout,失败后转异步.
 * Phase 4 完善:job 队列 + 重试 + 死信.
 */
@Service
public class AsyncMigrationService {

    private static final Logger log = LoggerFactory.getLogger(AsyncMigrationService.class);

    private final MigrationJobRepository jobRepository;
    private final DynamicTableManager tableManager;
    private final ObjectMapper objectMapper;

    @Value("${app.migration.lock-timeout-ms:5000}")
    private int lockTimeoutMs;

    public AsyncMigrationService(
            MigrationJobRepository jobRepository,
            DynamicTableManager tableManager,
            ObjectMapper objectMapper
    ) {
        this.jobRepository = jobRepository;
        this.tableManager = tableManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步执行迁移.成功返回 null,lock 超时抛 LockTimeoutException.
     */
    @Transactional
    public void executeSync(String collectionName, MigrationJobEntity.Operation op, Map<String, Object> payload) {
        // WeekR01:整段包进 try —否则 setLockTimeout 自身抛错会让 lock_timeout 残留
        try {
            tableManager.setLockTimeout(lockTimeoutMs);
            applyMutation(collectionName, op, payload);
            tableManager.resetLockTimeout();
        } catch (Exception e) {
            // 任何异常都先重置 lock_timeout,避免连接池复用时被污染
            try {
                tableManager.resetLockTimeout();
            } catch (Exception resetErr) {
                log.warn("[async-migration] resetLockTimeout 失败(可忽略): {}",
                        resetErr.getMessage());
            }
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (isLockTimeoutError(msg)) {
                throw new LockTimeoutException("锁表超时,建议转异步", e);
            }
            throw e;
        }
    }

    public UUID submitAsync(
            String collectionName, String tenantId,
            MigrationJobEntity.Operation op, Map<String, Object> payload,
            UUID createdBy
    ) {
        MigrationJobEntity job = new MigrationJobEntity();
        job.setId(UUID.randomUUID());
        job.setCollectionName(collectionName);
        job.setTenantId(tenantId);
        job.setOperation(op);
        try {
            job.setPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("payload 序列化失败", e);
        }
        job.setStatus(MigrationJobEntity.Status.PENDING);
        job.setCreatedAt(Instant.now());
        job.setCreatedBy(createdBy);

        jobRepository.save(job);
        runAsync(job.getId());
        return job.getId();
    }

    private void applyMutation(String collectionName, MigrationJobEntity.Operation op, Map<String, Object> payload) {
        switch (op) {
            case ADD_FIELD -> {
                String name = (String) payload.get("name");
                String type = (String) payload.get("type");
                String columnType = mapJsonbType(type);
                tableManager.addPhysicalColumn(collectionName, name, columnType);
            }
            case DROP_FIELD -> {
                String name = (String) payload.get("name");
                tableManager.dropPhysicalColumn(collectionName, name);
            }
            case RENAME_FIELD -> {
                String oldName = (String) payload.get("oldName");
                String newName = (String) payload.get("newName");
                tableManager.renamePhysicalColumn(collectionName, oldName, newName);
            }
            case ALTER_TYPE -> throw new UnsupportedOperationException("Week 8+ 支持");
        }
    }

    private String mapJsonbType(String type) {
        return switch (type) {
            case "text", "select", "multiSelect" -> "TEXT";
            case "number" -> "NUMERIC";
            case "boolean" -> "BOOLEAN";
            case "date", "datetime" -> "TIMESTAMPTZ";
            // Week 41 D1.2: attachment 存文件 key (TEXT,Week 42+ 接 MinIO)
            case "attachment" -> "TEXT";
            case "belongsTo", "hasMany" -> "UUID";
            case "formula" -> "TEXT";
            default -> throw new IllegalArgumentException("不支持的字段类型: " + type);
        };
    }

    private boolean isLockTimeoutError(String msg) {
        return msg != null && (msg.contains("lock timeout") || msg.contains("canceling statement"));
    }

    @Async
    @Transactional
    public void runAsync(UUID jobId) {
        MigrationJobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        job.setStatus(MigrationJobEntity.Status.RUNNING);
        job.setStartedAt(Instant.now());
        jobRepository.save(job);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(job.getPayloadJson(), Map.class);
            applyMutation(job.getCollectionName(), job.getOperation(), payload);
            job.setStatus(MigrationJobEntity.Status.COMPLETED);
            job.setFinishedAt(Instant.now());
            jobRepository.save(job);
            log.info("Migration job {} completed", jobId);
        } catch (Exception e) {
            log.error("Migration job {} failed", jobId, e);
            job.setStatus(MigrationJobEntity.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setFinishedAt(Instant.now());
            jobRepository.save(job);
        }
    }

    public MigrationJobEntity getJob(UUID jobId) {
        return jobRepository.findById(jobId).orElse(null);
    }

    public static class LockTimeoutException extends RuntimeException {
        public LockTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
