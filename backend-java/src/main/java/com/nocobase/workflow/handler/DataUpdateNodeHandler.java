package com.nocobase.workflow.handler;

import com.nocobase.meta.CollectionService;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowNodeHandler;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 数据更新节点(Week 41 复核 D4b.5)。
 *
 * <p>{@code MVP_SCOPE.md:64} 早已把"数据更新节点"标为 ✅,但引擎此前根本没有
 * 写回 collection 的能力,该节点类型完全不存在。本类补齐这一能力。
 *
 * <p>config 格式:
 * <pre>{@code
 * {
 *   "collection": "orders",          // 可选,默认取触发事件的 collection
 *   "recordId": "…",                 // 可选,默认取触发事件的 recordId
 *   "data": { "status": "approved" } // 要写入/合并的字段
 * }
 * }</pre>
 *
 * <p>写入走 {@link CollectionService#updateRecord},因此同样受 collection 级
 * 租户校验约束(跨租户更新会抛 403)。
 */
@Component
public class DataUpdateNodeHandler implements WorkflowNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(DataUpdateNodeHandler.class);

    private final CollectionService collectionService;

    @Autowired
    public DataUpdateNodeHandler(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Override
    public String type() {
        return "DATA_UPDATE";
    }

    @Override
    public NodeOutcome execute(NodeExecutionContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) ctx.node().getOrDefault("config", Map.of());

        String collection = (String) config.get("collection");
        String recordId = (String) config.get("recordId");
        Object data = config.get("data");

        // 缺省回退到触发事件的 collection / recordId
        if ((collection == null || collection.isBlank()) && ctx.triggeringEvent() != null) {
            collection = ctx.triggeringEvent().getCollectionName();
        }
        if ((recordId == null || recordId.isBlank()) && ctx.triggeringEvent() != null) {
            recordId = ctx.triggeringEvent().getRecordId();
        }

        if (collection == null || recordId == null || !(data instanceof Map)) {
            log.warn("[workflow {} node {}] DATA_UPDATE 缺少 collection / recordId / data,跳过",
                    ctx.instance().getId(), ctx.node().get("id"));
            return NodeOutcome.CONTINUE;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> patch = (Map<String, Object>) data;
            boolean updated = collectionService.updateRecord(
                    collection, recordId, patch, ctx.instance().getTenantId());
            log.info("[workflow {} node {}] DATA_UPDATE {}/{} updated={}",
                    ctx.instance().getId(), ctx.node().get("id"), collection, recordId, updated);
            return NodeOutcome.CONTINUE;
        } catch (Exception e) {
            log.error("[workflow {} node {}] DATA_UPDATE 失败: {}",
                    ctx.instance().getId(), ctx.node().get("id"), e.getMessage());
            return NodeOutcome.FAILED;
        }
    }
}
