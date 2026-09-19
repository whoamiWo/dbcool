package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import com.nocobase.ai.AgentToolContext;
import com.nocobase.wiki.WikiPageService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * create_page 工具 — 接真：委托 WikiPageService 创建 Wiki 文档。
 */
@Component
public class CreatePageTool implements AgentTool {

    private final WikiPageService wikiPageService;

    public CreatePageTool(WikiPageService wikiPageService) {
        this.wikiPageService = wikiPageService;
    }

    @Override public String name() { return "create_page"; }
    @Override public String description() { return "在指定知识库创建 Wiki 文档"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AgentToolContext ctx) {
        String title = (String) params.get("title");
        String slug = (String) params.get("slug");
        String content = (String) params.getOrDefault("content", "");
        Object kbIdObj = params.get("kbId");
        if (title == null || title.isBlank()) {
            return Map.of("error", "title 必填");
        }
        if (kbIdObj == null) {
            return Map.of("error", "kbId 必填");
        }
        UUID kbId;
        try { kbId = UUID.fromString(String.valueOf(kbIdObj)); }
        catch (Exception e) { return Map.of("error", "kbId 格式错误"); }
        try {
            var page = wikiPageService.create(kbId, null, title, slug, content, ctx.userId(), ctx.tenantId());
            return Map.of("pageId", page.getId().toString(), "title", page.getTitle(), "slug", page.getSlug());
        } catch (Exception e) {
            return Map.of("error", "创建失败: " + e.getMessage());
        }
    }
}
