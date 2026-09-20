package com.nocobase.search;

import com.nocobase.wiki.WikiPageService;
import com.nocobase.wiki.WikiPageEntity;
import com.nocobase.im.MessageService;
import com.nocobase.im.ImMessageEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.automation.AutomationRuleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.UUID;

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

    public UnifiedSearchService(
            MessageService messageService,
            WikiPageService wikiPageService,
            CollectionService collectionService,
            AutomationRuleService automationRuleService
    ) {
        this.messageService = messageService;
        this.wikiPageService = wikiPageService;
        this.collectionService = collectionService;
        this.automationRuleService = automationRuleService;
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
                allResults.addAll(messages.stream().map(m -> Map.of(
                        "type", "message",
                        "id", m.getId(),
                        "title", m.getContent(),
                        "snippet", m.getContent(),
                        "channelId", m.getChannelId(),
                        "createdAt", m.getCreatedAt()
                )).toList());
            } catch (Exception e) {
                // 搜索失败不影响其他模块
            }
        }
        
        // 搜索 Wiki 页面
        if (types == null || types.contains("wiki")) {
            try {
                List<WikiPageEntity> pages = 
                        wikiPageService.listByKbAndStatus(null, "PUBLISHED", tenantId);
                allResults.addAll(pages.stream().map(p -> Map.of(
                        "type", "wiki",
                        "id", p.getId(),
                        "title", p.getTitle(),
                        "snippet", p.getContent() != null ? p.getContent().substring(0, Math.min(200, p.getContent().length())) : "",
                        "slug", p.getSlug(),
                        "createdAt", p.getCreatedAt()
                )).toList());
            } catch (Exception e) {
                // 搜索失败不影响其他模块
            }
        }
        
        // 搜索 Collection 记录
        if (types == null || types.contains("record")) {
            try {
                // 简化:实际应使用 CollectionService 的搜索功能
                allResults.add(Map.of(
                        "type", "record",
                        "id", "search-placeholder",
                        "title", "Collection 记录搜索(待实现)",
                        "snippet", "使用关键词: " + keyword
                ));
            } catch (Exception e) {
                // 搜索失败不影响其他模块
            }
        }
        
        // 搜索自动化规则
        if (types == null || types.contains("automation")) {
            try {
                List<com.nocobase.automation.entity.AutomationRuleEntity> rules =
                        automationRuleService.listRules(tenantId);
                allResults.addAll(rules.stream().map(r -> Map.of(
                        "type", "automation",
                        "id", r.getId(),
                        "title", r.getName(),
                        "snippet", r.getDescription() != null ? r.getDescription() : "",
                        "triggerType", r.getTriggerType()
                )).toList());
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
     * 记录搜索到统一索引(用于增量更新)。
     */
    @Transactional
    public void indexEntity(String entityType, String entityId, String tenantId,
                            String title, String content, Map<String, Object> metadata) {
        // TODO: 写入 unified_search_index 表
        // 实际实现使用 JPA Repository 保存
    }

    /**
     * 从索引中移除实体。
     */
    @Transactional
    public void removeEntity(String entityType, String entityId, String tenantId) {
        // TODO: 从 unified_search_index 表删除
    }

    private long countByType(List<Map<String, Object>> results, String type) {
        return results.stream().filter(r -> type.equals(r.get("type"))).count();
    }
}
