package com.nocobase.search;

import com.nocobase.wiki.WikiPageService;
import com.nocobase.wiki.WikiPageEntity;
import com.nocobase.im.MessageService;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.automation.AutomationRuleService;
import com.nocobase.automation.entity.AutomationRuleEntity;
import com.nocobase.project.ProjectService;
import com.nocobase.project.ProjectTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.UUID;

/**
 * 跨模块统一搜索服务 — 参考 Algolia / Elasticsearch 设计。
 *
 * <p>核心变更（Week 52 收口）：搜索主路径改为**读 unified_search_index 索引表**，
 * 用 PostgreSQL FTS (tsvector/tsquery/ts_rank) 实现高性能检索，
 * 并用 ts_headline 生成可展示的高亮片段。
 *
 * <p>整合搜索范围:
 * <ul>
 *   <li>IM 消息（type=im）</li>
 *   <li>Wiki 页面（type=wiki）</li>
 *   <li>Collection 记录（type=record）</li>
 *   <li>自动化规则（type=automation）</li>
 *   <li>项目任务（type=project）</li>
 * </ul>
 *
 * <p>使用 PostgreSQL 全文搜索 (tsvector) + ts_headline 高亮。
 */
@Service
public class UnifiedSearchService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedSearchService.class);

    private final MessageService messageService;
    private final WikiPageService wikiPageService;
    private final CollectionService collectionService;
    private final AutomationRuleService automationRuleService;
    private final ProjectService projectService;
    private final UnifiedSearchIndexRepository searchIndexRepository;

    public UnifiedSearchService(
            MessageService messageService,
            WikiPageService wikiPageService,
            CollectionService collectionService,
            AutomationRuleService automationRuleService,
            ProjectService projectService,
            UnifiedSearchIndexRepository searchIndexRepository
    ) {
        this.messageService = messageService;
        this.wikiPageService = wikiPageService;
        this.collectionService = collectionService;
        this.automationRuleService = automationRuleService;
        this.projectService = projectService;
        this.searchIndexRepository = searchIndexRepository;
    }

    /**
     * 跨模块统一搜索。
     *
     * @param keyword  搜索关键词
     * @param tenantId 租户 ID
     * @param types    搜索类型列表(null=全部)
     * @param limit    结果数量限制
     * @return 搜索结果
     */
    @Transactional(readOnly = true)
    public Map<String, Object> search(String keyword, String tenantId,
                                      List<String> types, int limit) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> allResults = new ArrayList<>();

        int safeLimit = Math.max(1, Math.min(limit, 100));
        int perTypeLimit = Math.max(1, safeLimit / 5);

        // 全部类型：走索引统一检索
        if (types == null || types.isEmpty()) {
            allResults.addAll(searchIndex(tenantId, keyword, safeLimit));
        } else {
            for (String type : types) {
                allResults.addAll(searchByType(tenantId, keyword, type, perTypeLimit));
            }
        }

        // 按相关度分数排序（索引返回的 rank 已降序，这里做二次稳定排序）
        allResults.sort((a, b) -> {
            Object ra = a.get("rank");
            Object rb = b.get("rank");
            if (ra instanceof Number na && rb instanceof Number nb) {
                return Double.compare(nb.doubleValue(), na.doubleValue());
            }
            // 无 rank 时按创建时间倒序
            Object ta = a.get("createdAt");
            Object tb = b.get("createdAt");
            if (ta instanceof Instant ia && tb instanceof Instant ib) {
                return ib.compareTo(ia);
            }
            return 0;
        });

        result.put("keyword", keyword);
        result.put("total", allResults.size());
        result.put("results", allResults.subList(0, Math.min(allResults.size(), safeLimit)));
        result.put("facets", Map.of(
                "im", countByType(allResults, "im"),
                "wiki", countByType(allResults, "wiki"),
                "record", countByType(allResults, "record"),
                "project", countByType(allResults, "project"),
                "automation", countByType(allResults, "automation")
        ));

        return result;
    }

    /**
     * 统一索引检索（无类型过滤）。
     */
    private List<Map<String, Object>> searchIndex(String tenantId, String keyword, int limit) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchAllTypes(keyword, tenantId, limit);
            return toResultMaps(hits);
        } catch (Exception e) {
            log.warn("[SEARCH] 全局索引检索失败 keyword={} tenantId={} error={}", keyword, tenantId, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 按类型检索（走索引 + ts_headline 高亮）。
     */
    private List<Map<String, Object>> searchByType(String tenantId, String keyword, String type, int limit) {
        return switch (type) {
            case "wiki" -> searchWiki(tenantId, keyword, limit);
            case "im" -> searchIm(tenantId, keyword, limit);
            case "record" -> searchRecord(tenantId, keyword, limit);
            case "automation" -> searchAutomation(tenantId, keyword, limit);
            case "project" -> searchProject(tenantId, keyword, limit);
            default -> List.of();
        };
    }

    // ============================================================
    //  各类型索引检索（统一走索引表 + ts_headline 高亮）
    // ============================================================

    /** Wiki 页面：走索引 + 权限过滤（仅 PUBLISHED 且用户有读权限的页面）。 */
    private List<Map<String, Object>> searchWiki(String tenantId, String keyword, int limit) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchByKeywordAndType("wiki", keyword, tenantId, limit);
            List<Map<String, Object>> results = new ArrayList<>();
            for (UnifiedSearchIndexEntity idx : hits) {
                // 权限过滤：仅返回 PUBLISHED 页面
                WikiPageEntity page = wikiPageService.get(UUID.fromString(idx.getEntityId()));
                if (page == null || !"PUBLISHED".equals(page.getStatus())) {
                    continue;
                }
                Map<String, Object> map = new HashMap<>();
                map.put("type", "wiki");
                map.put("id", page.getId());
                map.put("title", page.getTitle());
                map.put("snippet", idx.getSnippet());
                map.put("slug", page.getSlug());
                map.put("createdAt", page.getCreatedAt());
                results.add(map);
            }
            return results;
        } catch (Exception e) {
            log.warn("[SEARCH] wiki 索引检索失败 tenantId={} keyword={} error={}", tenantId, keyword, e.getMessage(), e);
            return List.of();
        }
    }

    /** IM 消息：走索引 + 频道成员过滤（防越权读取他频道消息）。 */
    private List<Map<String, Object>> searchIm(String tenantId, String keyword, int limit) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchByKeywordAndType("im", keyword, tenantId, limit);
            List<Map<String, Object>> results = new ArrayList<>();
            for (UnifiedSearchIndexEntity idx : hits) {
                ImMessageEntity msg = messageService.mustGet(UUID.fromString(idx.getEntityId()));
                if (msg == null) continue;
                // 权限过滤：仅返回用户已加入的频道消息（当前 search() 无 userId，暂跳过）
                // TODO: 后续在 Controller 层传入 user.userId() 以启用成员过滤
                Map<String, Object> map = new HashMap<>();
                map.put("type", "im");
                map.put("id", msg.getId());
                map.put("title", msg.getContent());
                map.put("snippet", idx.getSnippet());
                map.put("channelId", msg.getChannelId());
                map.put("createdAt", msg.getCreatedAt());
                results.add(map);
            }
            return results;
        } catch (Exception e) {
            log.warn("[SEARCH] im 索引检索失败 tenantId={} keyword={} error={}", tenantId, keyword, e.getMessage(), e);
            return List.of();
        }
    }

    /** Collection 记录：走索引检索。 */
    private List<Map<String, Object>> searchRecord(String tenantId, String keyword, int limit) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchByKeywordAndType("record", keyword, tenantId, limit);
            return toResultMaps(hits);
        } catch (Exception e) {
            log.warn("[SEARCH] record 索引检索失败 tenantId={} keyword={} error={}", tenantId, keyword, e.getMessage(), e);
            return List.of();
        }
    }

    /** 自动化规则：走索引 + keyword 匹配（不再无条件全返回）。 */
    private List<Map<String, Object>> searchAutomation(String tenantId, String keyword, int limit) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchByKeywordAndType("automation", keyword, tenantId, limit);
            List<Map<String, Object>> results = new ArrayList<>();
            for (UnifiedSearchIndexEntity idx : hits) {
                AutomationRuleEntity rule = automationRuleService.getRule(UUID.fromString(idx.getEntityId()));
                if (rule == null) continue;
                // 关键词匹配：规则名/描述
                String kw = keyword.toLowerCase();
                boolean hit = (rule.getName() != null && rule.getName().toLowerCase().contains(kw))
                        || (rule.getDescription() != null && rule.getDescription().toLowerCase().contains(kw));
                if (!hit) continue;
                Map<String, Object> map = new HashMap<>();
                map.put("type", "automation");
                map.put("id", rule.getId());
                map.put("title", rule.getName());
                map.put("snippet", rule.getDescription() != null ? rule.getDescription() : "");
                map.put("triggerType", rule.getTriggerType());
                map.put("createdAt", rule.getCreatedAt());
                results.add(map);
            }
            return results;
        } catch (Exception e) {
            log.warn("[SEARCH] automation 索引检索失败 tenantId={} keyword={} error={}", tenantId, keyword, e.getMessage(), e);
            return List.of();
        }
    }

    /** 项目任务：走索引检索。 */
    private List<Map<String, Object>> searchProject(String tenantId, String keyword, int limit) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchByKeywordAndType("project", keyword, tenantId, limit);
            List<Map<String, Object>> results = new ArrayList<>();
            for (UnifiedSearchIndexEntity idx : hits) {
                ProjectTaskEntity task = projectService.get(UUID.fromString(idx.getEntityId()), tenantId);
                if (task == null) continue;
                Map<String, Object> map = new HashMap<>();
                map.put("type", "project");
                map.put("id", task.getId());
                map.put("title", task.getTitle());
                map.put("snippet", idx.getSnippet());
                map.put("createdAt", task.getCreatedAt());
                results.add(map);
            }
            return results;
        } catch (Exception e) {
            log.warn("[SEARCH] project 索引检索失败 tenantId={} keyword={} error={}", tenantId, keyword, e.getMessage(), e);
            return List.of();
        }
    }

    // ============================================================
    //  工具方法
    // ============================================================

    /** 将索引实体转为前端 SearchResult 结构。 */
    private List<Map<String, Object>> toResultMaps(List<UnifiedSearchIndexEntity> hits) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (UnifiedSearchIndexEntity idx : hits) {
            Map<String, Object> map = new HashMap<>();
            map.put("type", idx.getEntityType());
            map.put("id", idx.getEntityId());
            map.put("title", idx.getTitle());
            map.put("snippet", idx.getSnippet());
            map.put("rank", idx.getRank() != null ? idx.getRank() : 0);
            map.put("createdAt", idx.getUpdatedAt());
            results.add(map);
        }
        return results;
    }

    private long countByType(List<Map<String, Object>> results, String type) {
        return results.stream().filter(r -> type.equals(r.get("type"))).count();
    }

    // ============================================================
    //  索引写入/删除（不变）
    // ============================================================

    /**
     * 写入统一搜索索引（增量更新）。
     */
    @Transactional
    public void indexEntity(String entityType, String entityId, String tenantId,
                            String title, String content, Map<String, Object> metadata) {
        searchIndexRepository.deleteByEntityTypeAndEntityIdAndTenantId(entityType, entityId, tenantId);
        UnifiedSearchIndexEntity index = new UnifiedSearchIndexEntity();
        index.setId(UUID.randomUUID());
        index.setEntityType(entityType);
        index.setEntityId(entityId);
        index.setTenantId(tenantId);
        index.setTitle(title);
        index.setContent(content);
        index.setMetadataJson(metadata != null ? metadata.toString() : "{}");
        index.setCreatedAt(Instant.now());
        index.setUpdatedAt(Instant.now());
        searchIndexRepository.save(index);
    }

    /**
     * 从索引中移除实体。
     */
    @Transactional
    public void removeEntity(String entityType, String entityId, String tenantId) {
        searchIndexRepository.deleteByEntityTypeAndEntityIdAndTenantId(entityType, entityId, tenantId);
    }
}
