package com.nocobase.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 记录变更事件(Week 41 D4a — 触发器真实化基础).
 *
 * <p>由 CollectionController 在事务提交后发布(AFTER_COMMIT),
 * 由 WorkflowTriggerListener 等监听器消费。
 *
 * <p>字段:
 * <ul>
 *   <li>changeType — CREATE / UPDATE / DELETE</li>
 *   <li>collectionName — collection 名(如 "orders")</li>
 *   <li>recordId — 记录 ID(UUID 字符串)</li>
 *   <li>data — 记录数据(UPDATE 时含变更后全量,DELETE 时为 null)</li>
 *   <li>tenantId — 租户 ID</li>
 *   <li>userId — 触发者</li>
 *   <li>occurredAt — 事件时间戳</li>
 * </ul>
 *
 * @see org.springframework.transaction.event.TransactionalEventListener
 */
public class RecordChangeEvent {

    public enum ChangeType { CREATE, UPDATE, DELETE }

    private final ChangeType changeType;
    private final String collectionName;
    private final String recordId;
    private final Map<String, Object> data;
    private final String tenantId;
    private final UUID userId;
    private final Instant occurredAt;

    public RecordChangeEvent(
            ChangeType changeType,
            String collectionName,
            String recordId,
            Map<String, Object> data,
            String tenantId,
            UUID userId
    ) {
        this.changeType = changeType;
        this.collectionName = collectionName;
        this.recordId = recordId;
        this.data = data;
        this.tenantId = tenantId;
        this.userId = userId;
        this.occurredAt = Instant.now();
    }

    public ChangeType getChangeType() { return changeType; }
    public String getCollectionName() { return collectionName; }
    public String getRecordId() { return recordId; }
    public Map<String, Object> getData() { return data; }
    public String getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public Instant getOccurredAt() { return occurredAt; }

    @Override
    public String toString() {
        return "RecordChangeEvent{" +
                "type=" + changeType +
                ", collection='" + collectionName + '\'' +
                ", recordId='" + recordId + '\'' +
                ", tenantId='" + tenantId + '\'' +
                ", at=" + occurredAt +
                '}';
    }
}
