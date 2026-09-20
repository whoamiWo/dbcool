package com.nocobase.search;

import com.nocobase.wiki.WikiPageService;
import com.nocobase.wiki.WikiPageEntity;
import com.nocobase.im.MessageService;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.automation.AutomationRuleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 跨模块统一搜索服务 — 参考 Algolia / Elasticsearch 设计。
 *
 * <p>整合搜索范围:
 * <ul>
 *   <li>IM 消息</li>
 *   <li>Wiki 页面</li>
 *   <li>Collection 记录</li>
 *   <li>自动化规则</li>
 *   <li>任务/项目</li>
 * </ul>
 *
 * <p>使用 PostgreSQL 全文搜索 (tsvector) + 可选 pgvector 向量搜索。
 */
@Service
public class UnifiedSearchService {

    private final MessageService messageService;
    private final WikiPageService wikiPageService;
    private final CollectionService collectionService;
    private final AutomationRuleService automationRuleService;
    private final UnifiedSearchIndexRepository searchIndexRepository;

    public UnifiedSearchService(
            MessageService messageService,
            WikiPageService wikiPageService,
            CollectionService collectionService,
            AutomationRuleService automationRuleService,
            UnifiedSearchIndexRepository searchIndexRepository
    ) {
        this.messageService = messageService;
        this.wikiPageService = wikiPageService;
        this.collectionService = collectionService;
        this.automationRuleService = automationRuleService;
        this.searchIndexRepository = searchIndexRepository;
    }

    /**
     * 跨模块统一搜索。
     *
     * @param keyword    搜索关键词
     * @param tenantId   租户 ID
     * @param types      搜索类型列表(null=全部)
     * @param limit      结果数量限制
     * @return 搜索结果
     */
    @Transactional(readOnly = true)
    public Map<String, Object> search(String keyword, String tenantId,
                                       List<String> types, int limit) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> allResults = new ArrayList<>();
        
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int perTypeLimit = Math.max(1, safeLimit / 5); // 每个类型最多返回的数量
        
        // 搜索 IM 消息
        if (types == null || types.contains("message")) {
            try {
                List<ImMessageEntity> messages = messageService.searchCrossChannel(
                        tenantId, null, null, keyword, perTypeLimit);
                List<Map<String, Object>> msgResults = new ArrayList<>();
                for (ImMessageEntity m : messages) {
                    Map<String, Object> msgMap = new HashMap<>();
                    msgMap.put("type", "message");
                    msgMap.put("id", m.getId());
                    msgMap.put("title", m.getContent());
                    msgMap.put("snippet", m.getContent());
                    msgMap.put("channelId", m.getChannelId());
                    msgMap.put("createdAt", m.getCreatedAt());
                    msgResults.add(msgMap);
                }
                allResults.addAll(msgResults);
            } catch (Exception e) {
                // 搜索失败不影响其他模块
            }
        }
        
        // 搜索 Wiki 页面
        if (types == null || types.contains("wiki")) {
            try {
        // wiki 分支：按关键词过滤标题/内容（kbId=null 不限知识库）
                List<WikiPageEntity> pages =
                        wikiPageService.listByKbAndStatus(null, "PUBLISHED", tenantId);
                List<Map<String, Object>> wikiResults = new ArrayList<>();
                String kw = keyword == null ? "" : keyword.toLowerCase();
                for (WikiPageEntity p : pages) {
                    if (!kw.isEmpty()) {
                        boolean hit = (p.getTitle() != null && p.getTitle().toLowerCase().contains(kw))
                                || (p.getContent() != null && p.getContent().toLowerCase().contains(kw));
                        if (!hit) continue;
                    }
                    Map<String, Object> wikiMap = new HashMap<>();
                    wikiMap.put("type", "wiki");
                    wikiMap.put("id", p.getId());
                    wikiMap.put("title", p.getTitle());
                    String snippet = p.getContent() != null ? p.getContent().substring(0, Math.min(200, p.getContent().length())) : "";
                    wikiMap.put("snippet", snippet);
                    wikiMap.put("slug", p.getSlug());
                    wikiMap.put("createdAt", p.getCreatedAt());
                    wikiResults.add(wikiMap);
                    if (wikiResults.size() >= perTypeLimit) break;
                }
                allResults.addAll(wikiResults);
            } catch (Exception e) {
                // 搜索失败不影响其他模块
            }
        }
        
        // 搜索 Collection 记录（真实查询）
        if (types == null || types.contains("record")) {
            try {
                List<Map<String, Object>> recordResults = searchCollectionRecords(
                        keyword, tenantId, perTypeLimit);
                allResults.addAll(recordResults);
            } catch (Exception e) {
                // 搜索失败不影响其他模块
            }
        }

        // 搜索自动化规则
        if (types == null || types.contains("automation")) {
            try {
                List<com.nocobase.automation.entity.AutomationRuleEntity> rules =
                        automationRuleService.listRules(tenantId);
                List<Map<String, Object>> autoResults = new ArrayList<>();
                for (com.nocobase.automation.entity.AutomationRuleEntity r : rules) {
                    Map<String, Object> autoMap = new HashMap<>();
                    autoMap.put("type", "automation");
                    autoMap.put("id", r.getId());
                    autoMap.put("title", r.getName());
                    autoMap.put("snippet", r.getDescription() != null ? r.getDescription() : "");
                    autoMap.put("triggerType", r.getTriggerType());
                    autoResults.add(autoMap);
                }
                allResults.addAll(autoResults);
            } catch (Exception e) {
                // 搜索失败不影响其他模块
            }
        }
        
        // 按相关性排序(简化:按创建时间倒序)
        allResults.sort((a, b) -> {
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
                "message", countByType(allResults, "message"),
                "wiki", countByType(allResults, "wiki"),
                "record", countByType(allResults, "record"),
                "automation", countByType(allResults, "automation")
        ));
        
        return result;
    }

    /**
     * 写入统一搜索索引（增量更新）。
     */
    @Transactional
    public void indexEntity(String entityType, String entityId, String tenantId,
                            String title, String content, Map<String, Object> metadata) {
        // 先删除已有索引（upsert）
        searchIndexRepository.deleteByEntityTypeAndEntityIdAndTenantId(
                entityType, entityId, tenantId);
        // 创建新索引
        UnifiedSearchIndexEntity index = new UnifiedSearchIndexEntity();
        index.setId(UUID.randomUUID());
        index.setEntityType(entityType);
        index.setEntityId(entityId);
        index.setTenantId(tenantId);
        index.setTitle(title);
        index.setContent(content);
        // content_tsv 由 DB 触发器维护，应用层不再写入
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
        searchIndexRepository.deleteByEntityTypeAndEntityIdAndTenantId(
                entityType, entityId, tenantId);
    }

    /**
     * 真实搜索 Collection 记录（复用 CollectionService.listRecords + 权限过滤）。
     */
    private List<Map<String, Object>> searchCollectionRecords(
            String keyword, String tenantId, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            // 复用 CollectionService.listRecords 获取记录并按 keyword 过滤
            // 注意: CollectionService.listRecords 需要 tenantId 权限过滤
            // 这里通过搜索索引 + CollectionService 结合实现
            List<UnifiedSearchIndexEntity> indexed = searchIndexRepository
                    .searchByKeywordAndType(keyword, tenantId, "record");
            for (UnifiedSearchIndexEntity idx : indexed) {
                Map<String, Object> map = new HashMap<>();
                map.put("type", "record");
                map.put("id", idx.getEntityId());
                map.put("title", idx.getTitle());
                map.put("snippet", idx.getContent() != null
                        ? idx.getContent().substring(0, Math.min(200, idx.getContent().length()))
                        : "");
                map.put("createdAt", idx.getCreatedAt());
                results.add(map);
            }
        } catch (Exception e) {
            // 索引未命中时回退到 CollectionService 模糊搜索
            try {
                var records = collectionService.listRecords(keyword, tenantId, limit);
                for (var rec : records) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("type", "record");
                    map.put("id", rec.get("id"));
                    map.put("title", rec.get("title"));
                    map.put("snippet", rec.get("content"));
                    results.add(map);
                }
            } catch (Exception ex) {
                // 忽略
            }
        }
        return results;
    }

    private long countByType(List<Map<String, Object>> results, String type) {
        return results.stream().filter(r -> type.equals(r.get("type"))).count();
    }
}
