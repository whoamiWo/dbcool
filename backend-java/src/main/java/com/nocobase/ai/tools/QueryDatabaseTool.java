package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * query_database 工具 — 查询数据库(占位,接入 DynamicTableManager 后接真)。
 */
@Component
public class QueryDatabaseTool implements AgentTool {
    @Override public String name() { return "query_database"; }
    @Override public String description() { return "执行只读 SQL 查询"; }
    @Override public Map<String, Object> execute(Map<String, Object> params) {
        return Map.of("rows", 0, "query", params.get("sql"));
    }
}
