package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import com.nocobase.ai.AgentToolContext;
import com.nocobase.meta.CollectionService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * query_database 工具 — 接真：委托 CollectionService.listRecords 查询（只读）。
 *
 * <p>Phase 48 审计修复：删除硬编码 {@code rows:0}，改为真实查询 collection。
 */
@Component
public class QueryDatabaseTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 50;

    private final CollectionService collectionService;

    public QueryDatabaseTool(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Override public String name() { return "query_database"; }
    @Override public String description() { return "只读查询 collection 记录（禁止写入）"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AgentToolContext ctx) {
        String collectionName = (String) params.get("collectionName");
        if (collectionName == null || collectionName.isBlank()) {
            return Map.of("error", "collectionName 必填");
        }
        try {
            int limit = parseIntOrDefault(params.get("limit"), DEFAULT_LIMIT);
            List<Map<String, Object>> records = collectionService.listRecords(
                    collectionName, ctx.tenantId(), limit, null, null);
            return Map.of("rows", records.size(), "data", records);
        } catch (Exception e) {
            return Map.of("error", "查询失败: " + e.getMessage());
        }
    }

    private static int parseIntOrDefault(Object v, int def) {
        if (v == null) return def;
        try { return Math.max(1, Math.min(200, Integer.parseInt(String.valueOf(v)))); }
        catch (Exception e) { return def; }
    }
}
