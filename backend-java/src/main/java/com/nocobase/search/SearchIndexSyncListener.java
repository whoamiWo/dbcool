package com.nocobase.search;

import com.nocobase.event.RecordChangeEvent;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.im.MessageService;
import com.nocobase.project.ProjectTaskEntity;
import com.nocobase.project.ProjectService;
import com.nocobase.wiki.WikiPageEntity;
import com.nocobase.wiki.WikiPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全局搜索索引同步监听器（Week 52 收口 — 接真）。
 *
 * <p>此前 unified_search_index 表（V28）已存在但无写入器，导致搜索恒空。
 * 本监听器在事务提交后消费 {@link RecordChangeEvent}，把 wiki / record / im / project
 * 四类实体的真实变更同步写入搜索索引，供 {@link UnifiedSearchService} 查询。
 *
 * <p>与 WebhookSubscriptionListener 共用同一套事件语义：
 * {@code AFTER_COMMIT + fallbackExecution + @Async}，不阻塞主流程。
 */
@Component
public class SearchIndexSyncListener {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexSyncListener.class);

    private final UnifiedSearchService searchService;
    private final WikiPageService wikiPageService;
    private final MessageService messageService;
    private final ProjectService projectService;

    public SearchIndexSyncListener(UnifiedSearchService searchService,
                                    WikiPageService wikiPageService,
                                    MessageService messageService,
                                    ProjectService projectService) {
        this.searchService = searchService;
        this.wikiPageService = wikiPageService;
        this.messageService = messageService;
        this.projectService = projectService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRecordChange(RecordChangeEvent event) {
        String collection = event.getCollectionName();
        String recordId = event.getRecordId();
        String tenantId = event.getTenantId();

        try {
            switch (collection) {
                case "wiki_page":
                    syncWikiPage(recordId, tenantId, event.getChangeType());
                    break;
                case "im_message":
                    syncImMessage(recordId, tenantId, event.getChangeType());
                    break;
                case "project_task":
                    syncProjectTask(recordId, tenantId, event.getChangeType());
                    break;
                case "record":
                default:
                    syncRecord(event);
                    break;
            }
        } catch (Exception e) {
            // 索引同步失败不影响主流程；由日志可观测
            log.warn("搜索索引同步失败 collection={} recordId={} error={}",
                    collection, recordId, e.getMessage());
        }
    }

    private void syncWikiPage(String recordId, String tenantId, RecordChangeEvent.ChangeType type) {
        if (type == RecordChangeEvent.ChangeType.DELETE) {
            searchService.removeEntity("wiki", recordId, tenantId);
            return;
        }
        try {
            UUID uuid = UUID.fromString(recordId);
            WikiPageEntity page = wikiPageService.get(uuid);
            if (page == null) {
                searchService.removeEntity("wiki", recordId, tenantId);
                return;
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("slug", page.getSlug());
            meta.put("kbId", page.getKnowledgeBaseId());
            meta.put("status", page.getStatus());
            searchService.indexEntity("wiki", recordId, tenantId,
                    page.getTitle() != null ? page.getTitle() : "",
                    page.getContent() != null ? page.getContent() : "",
                    meta);
        } catch (Exception e) {
            log.warn("wiki 索引同步失败 recordId={} error={}", recordId, e.getMessage());
        }
    }

    private void syncImMessage(String recordId, String tenantId, RecordChangeEvent.ChangeType type) {
        if (type == RecordChangeEvent.ChangeType.DELETE) {
            searchService.removeEntity("im", recordId, tenantId);
            return;
        }
        try {
            UUID uuid = UUID.fromString(recordId);
            ImMessageEntity m = messageService.mustGet(uuid);
            if (m == null) {
                searchService.removeEntity("im", recordId, tenantId);
                return;
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("channelId", m.getChannelId());
            meta.put("senderId", m.getSenderId());
            searchService.indexEntity("im", recordId, tenantId,
                    m.getContent() != null ? m.getContent() : "",
                    m.getContent() != null ? m.getContent() : "",
                    meta);
        } catch (Exception e) {
            log.warn("im 索引同步失败 recordId={} error={}", recordId, e.getMessage());
        }
    }

    private void syncProjectTask(String recordId, String tenantId, RecordChangeEvent.ChangeType type) {
        if (type == RecordChangeEvent.ChangeType.DELETE) {
            searchService.removeEntity("project", recordId, tenantId);
            return;
        }
        try {
            UUID uuid = UUID.fromString(recordId);
            ProjectTaskEntity t = projectService.get(uuid, tenantId);
            if (t == null) {
                searchService.removeEntity("project", recordId, tenantId);
                return;
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("projectId", t.getProjectId());
            meta.put("status", t.getStatus());
            meta.put("assigneeId", t.getAssigneeId());
            searchService.indexEntity("project", recordId, tenantId,
                    t.getTitle() != null ? t.getTitle() : "",
                    t.getDescription() != null ? t.getDescription() : "",
                    meta);
        } catch (Exception e) {
            log.warn("project 索引同步失败 recordId={} error={}", recordId, e.getMessage());
        }
    }

    private void syncRecord(RecordChangeEvent event) {
        // Collection 记录走统一 record 类型索引
        Map<String, Object> data = event.getData();
        String title = data != null ? String.valueOf(data.getOrDefault("title", "")) : "";
        String content = data != null ? String.valueOf(data.getOrDefault("content", "")) : "";
        if (event.getChangeType() == RecordChangeEvent.ChangeType.DELETE) {
            searchService.removeEntity("record", event.getRecordId(), event.getTenantId());
        } else {
            searchService.indexEntity("record", event.getRecordId(), event.getTenantId(),
                    title, content, data);
        }
    }
}