package com.nocobase.playbook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Playbook 运行实例（折中版，Phase 48 F2）。
 *
 * <p>Checklist 与事件日志走 JSONB 整读整写（不为追加事件做逐条插入）；
 * {@code dueAt} 为 SLA 到期时间，到期未完成 → {@code OVERDUE}；
 * 完成后生成 Wiki 复盘页并记录 {@code retrospectivePageId}。
 */
@Entity
@Table(name = "playbook_run")
public class PlaybookRunEntity {

    public static final String RUNNING = "RUNNING";
    public static final String FINISHED = "FINISHED";
    public static final String OVERDUE = "OVERDUE";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "playbook_id", nullable = false)
    private UUID playbookId;

    @Column(name = "channel_id")
    private UUID channelId;

    /** 对应 workflow_instances.id。 */
    @Column(name = "instance_id")
    private UUID instanceId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = RUNNING;

    /** SLA 到期时间。 */
    @Column(name = "due_at")
    private Instant dueAt;

    /** [{title, done, dueAt}] */
    @Column(name = "checklist_json", columnDefinition = "jsonb", nullable = false)
    private String checklistJson = "[]";

    /** [{at, event, detail}] 整读整写。 */
    @Column(name = "events_json", columnDefinition = "jsonb", nullable = false)
    private String eventsJson = "[]";

    @Column(name = "retrospective_page_id")
    private UUID retrospectivePageId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public PlaybookRunEntity() {}

    // getters / setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public UUID getPlaybookId() { return playbookId; }
    public void setPlaybookId(UUID playbookId) { this.playbookId = playbookId; }
    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public UUID getInstanceId() { return instanceId; }
    public void setInstanceId(UUID instanceId) { this.instanceId = instanceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public String getChecklistJson() { return checklistJson; }
    public void setChecklistJson(String checklistJson) { this.checklistJson = checklistJson; }
    public String getEventsJson() { return eventsJson; }
    public void setEventsJson(String eventsJson) { this.eventsJson = eventsJson; }
    public UUID getRetrospectivePageId() { return retrospectivePageId; }
    public void setRetrospectivePageId(UUID retrospectivePageId) { this.retrospectivePageId = retrospectivePageId; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
