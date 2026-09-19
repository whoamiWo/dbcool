package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import com.nocobase.ai.AgentToolContext;
import com.nocobase.wiki.WikiSearchService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * search_wiki 工具 — 接真：委托 WikiSearchService 全文检索。
 */
@Component
public class SearchWikiTool implements AgentTool {

    private final WikiSearchService wikiSearchService;

    public SearchWikiTool(WikiSearchService wikiSearchService) {
        this.wikiSearchService = wikiSearchService;
    }

    @Override public String name() { return "search_wiki"; }
    @Override public String description() { return "搜索 Wiki 知识库，返回匹配页面列表"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AgentToolContext ctx) {
        String query = (String) params.getOrDefault("query", "");
        if (query == null || query.isBlank()) {
            return Map.of("error", "query 不能为空");
        }
        UUID kbId = parseOptionalUuid(params, "kbId");
        try {
            var page = wikiSearchService.search(query, kbId, ctx.tenantId(), 0, 10);
            List<Map<String, Object>> items = page.getContent().stream()
                    .map(p -> Map.<String, Object>of("id", p.getId().toString(),
                                                     "title", p.getTitle(),
                                                     "slug", p.getSlug()))
                    .toList();
            return Map.of("total", page.getTotalElements(), "items", items);
        } catch (Exception e) {
            return Map.of("error", "搜索失败: " + e.getMessage());
        }
    }

    private static UUID parseOptionalUuid(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        try { return UUID.fromString(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
