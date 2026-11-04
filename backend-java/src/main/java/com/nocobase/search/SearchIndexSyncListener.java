package com.nocobase.search;

import com.nocobase.event.RecordChangeEvent;
import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.meta.FieldDef;
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
import java.util.Set;
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
    /** A-6：需要集合元数据来推断可检索字段（业务集合没有 title/content 列）。 */
    private final CollectionService collectionService;

    public SearchIndexSyncListener(UnifiedSearchService searchService,
                                    WikiPageService wikiPageService,
                                    MessageService messageService,
                                    ProjectService projectService,
                                    CollectionService collectionService) {
        this.searchService = searchService;
        this.wikiPageService = wikiPageService;
        this.messageService = messageService;
        this.projectService = projectService;
        this.collectionService = collectionService;
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

    /**
     * A-6 修复：业务记录索引写入。
     *
     * <p>修复前只取 {@code data.title} / {@code data.content} —— 业务集合的字段名
     * 通常是 {@code name}/{@code customer_name}/{@code remark}…，取不到就写空，
     * 于是"业务记录搜不出来"；而 {@code metadata} 直接塞整条记录，既泄露全字段，
     * 又丢了所属 collection 信息（搜索侧无法做 ACL 判定）。
     *
     * <p>现在的推断规则（决策 D10，纯推断，不新增元数据配置）：
     * <ol>
     *   <li>标题：优先字段名 {@code name}/{@code title} 的文本字段；否则第一个
     *       {@code text} 字段；再否则 {@code {集合名} #{recordId 前 8 位}}</li>
     *   <li>正文：拼接所有文本类字段值（text/email/url/phone/select/multiSelect），
     *       截断 4096 字符</li>
     *   <li>元数据白名单：{@code collection / recordId / displayValue}，不放整条记录</li>
     * </ol>
     */
    /**
     * 存量重建入口（T5.1）：用与增量同步**完全相同**的推断逻辑重建一条记录索引。
     *
     * <p>只给 {@code SearchReindexRunner} 使用，避免维护第二套字段推断代码。
     */
    public void reindexRecord(String collection, String recordId, String tenantId,
                              Map<String, Object> data) {
        syncRecord(new RecordChangeEvent(RecordChangeEvent.ChangeType.UPDATE,
                collection, recordId, data, tenantId, null));
    }

    private void syncRecord(RecordChangeEvent event) {
        if (event.getChangeType() == RecordChangeEvent.ChangeType.DELETE) {
            searchService.removeEntity("record", event.getRecordId(), event.getTenantId());
            return;
        }
        Map<String, Object> data = event.getData() == null ? Map.of() : event.getData();
        String collection = event.getCollectionName();
        List<FieldDef> fields = resolveFields(collection, event.getTenantId());

        String title = inferTitle(data, fields, collection, event.getRecordId());
        String content = buildContent(data, fields);
        Map<String, Object> meta = new HashMap<>();
        meta.put("collection", collection);
        meta.put("recordId", event.getRecordId());
        meta.put("displayValue", title);

        searchService.indexEntity("record", event.getRecordId(), event.getTenantId(),
                title, content, meta);
    }

    private List<FieldDef> resolveFields(String collection, String tenantId) {
        if (collection == null) return List.of();
        try {
            CollectionMetaEntity meta = collectionService.get(collection, tenantId);
            return meta == null ? List.of() : collectionService.parseFields(meta);
        } catch (Exception e) {
            log.warn("集合元数据解析失败 collection={} error={}", collection, e.getMessage());
            return List.of();
        }
    }

    private static final Set<String> SEARCHABLE_TYPES = Set.of(
            "text", "email", "url", "phone", "select", "multiSelect", "longText");

    private static String inferTitle(Map<String, Object> data, List<FieldDef> fields,
                                     String collection, String recordId) {
        for (String preferred : List.of("name", "title")) {
            Object v = data.get(preferred);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        for (FieldDef f : fields) {
            if ("text".equalsIgnoreCase(f.type())) {
                Object v = data.get(f.name());
                if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
            }
        }
        String idPart = recordId == null ? "unknown"
                : recordId.length() > 8 ? recordId.substring(0, 8) : recordId;
        String label = collection == null || collection.isBlank() ? "记录" : collection;
        return label + " #" + idPart;
    }

    private static String buildContent(Map<String, Object> data, List<FieldDef> fields) {
        StringBuilder sb = new StringBuilder();
        if (fields.isEmpty()) {
            // 元数据不可得时退化为全部标量值（至少比空内容强），但不写进 metadata
            data.values().stream().filter(v -> v instanceof CharSequence || v instanceof Number)
                    .forEach(v -> sb.append(v).append(' '));
        } else {
            for (FieldDef f : fields) {
                if (!SEARCHABLE_TYPES.contains(f.type())) continue;
                Object v = data.get(f.name());
                if (v == null) continue;
                String text = String.valueOf(v);
                if (text.isBlank()) continue;
                if (f.label() != null && !f.label().isBlank()) sb.append(f.label()).append('：');
                sb.append(text).append(' ');
            }
        }
        String content = sb.toString().trim();
        return content.length() > 4096 ? content.substring(0, 4096) : content;
    }
}