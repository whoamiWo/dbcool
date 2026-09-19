package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * create_page 工具 — 创建 Wiki 文档(占位,接入 WikiPageService 后接真)。
 */
@Component
public class CreatePageTool implements AgentTool {
    @Override public String name() { return "create_page"; }
    @Override public String description() { return "在知识库创建 Wiki 文档"; }
    @Override public Map<String, Object> execute(Map<String, Object> params) {
        return Map.of("pageId", "TODO_create_page", "title", params.get("title"));
    }
}
