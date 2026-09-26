package com.nocobase.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.config.AsyncTask;
import com.nocobase.config.AsyncTaskPublishException;
import com.nocobase.config.AsyncTaskPublisher;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PHASE 55 Stage 2 — 异步迁移服务。
 *
 * <p>改造:submitAsync 不再使用 @Async,而是发布到 RabbitMQ 任务队列,
 * 由 {@link MigrationTaskHandler} 消费,失败自动退避重试,
 * 超限进入死信队列,保证迁移任务可补偿。
 */
@Service
public class AsyncMigrationService {

    private static final Logger log = LoggerFactory.getLogger(AsyncMigrationService.class);
    static final String TASK_TYPE = "meta.migration";

    private final MigrationJobRepository jobRepository;
    private final DynamicTableManager tableManager;
    private final ObjectMapper objectMapper;
    private final AsyncTaskPublisher publisher;

    @Value("${app.migration.lock-timeout-ms:5000}")
    private int lockTimeoutMs;

    public AsyncMigrationService(
            MigrationJobRepository jobRepository,
            DynamicTableManager tableManager,
            ObjectMapper objectMapper,
            AsyncTaskPublisher publisher
    ) {
        this.jobRepository = jobRepository;
        this.tableManager = tableManager;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    /**
     * 同步执行迁移。成功返回 null,lock 超时抛 LockTimeoutException。
     */
    @Transactional
    public void executeSync(String collectionName, MigrationJobEntity.Operation op, Map<String, Object> payload) {
        try {
            tableManager.setLockTimeout(lockTimeoutMs);
            applyMutation(collectionName, op, payload);
            tableManager.resetLockTimeout();
        } catch (Exception e) {
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

        // 改造:发布到 MQ,由 MigrationTaskHandler 消费
        Map<String, Object> taskPayload = new HashMap<>();
        taskPayload.put("jobId", job.getId().toString());
        taskPayload.put("collectionName", collectionName);
        taskPayload.put("operation", op.name());
        taskPayload.put("payload", payload);
        AsyncTask task = new AsyncTask(
                TASK_TYPE,
                job.getId().toString(),
                tenantId,
                taskPayload,
                createdBy,
                tenantId + ":migration:" + job.getId()
        );
        try {
            publisher.publish(task);
        } catch (AsyncTaskPublishException e) {
            // MQ 不可达:回滚事务,标记作业失败
            job.setStatus(MigrationJobEntity.Status.FAILED);
            job.setErrorMessage("MQ 不可达,任务发布失败: " + e.getMessage());
            job.setFinishedAt(Instant.now());
            jobRepository.save(job);
            throw e;
        }
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
            case ALTER_TYPE -> {
                // PHASE 55 Stage 4: 支持 ALTER_TYPE
                String name = (String) payload.get("name");
                String type = (String) payload.get("type");
                String columnType = mapJsonbType(type);
                tableManager.alterPhysicalColumn(collectionName, name, columnType);
            }
        }
    }

    private String mapJsonbType(String type) {
        return switch (type) {
            case "text", "select", "multiSelect" -> "TEXT";
            case "number" -> "NUMERIC";
            case "boolean" -> "BOOLEAN";
            case "date", "datetime" -> "TIMESTAMPTZ";
            case "attachment" -> "TEXT";
            case "belongsTo", "hasMany" -> "UUID";
            case "formula" -> "TEXT";
            default -> throw new IllegalArgumentException("不支持的字段类型: " + type);
        };
    }

    private boolean isLockTimeoutError(String msg) {
        return msg != null && (msg.contains("lock timeout") || msg.contains("canceling statement"));
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
