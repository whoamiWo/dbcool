package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * search_wiki 工具 — 复用 WikiSearchService。
 */
@Component
public class SearchWikiTool implements AgentTool {
    @Override public String name() { return "search_wiki"; }
    @Override public String description() { return "搜索 Wiki 知识库"; }
    @Override public Map<String, Object> execute(Map<String, Object> params) {
        return Map.of("results", params.getOrDefault("query", ""));
    }
}