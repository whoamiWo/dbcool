package com.nocobase.workflow.handler;

import com.nocobase.wiki.WikiPageEntity;
import com.nocobase.wiki.WikiPageRepository;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowNodeHandler;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wiki 发布节点处理器 — WIKI_PUBLISH 节点类型。
 *
 * <p>将工作流实例绑定的文档页面状态从 DRAFT 切换到 PUBLISHED。
 * <p>执行成功后继续下一个节点（CONTINUE）。
 */
@Component
public class WikiPublishNodeHandler implements WorkflowNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(WikiPublishNodeHandler.class);

    private final WikiPageRepository pageRepository;

    public WikiPublishNodeHandler(WikiPageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    @Override
    public String type() {
        return "WIKI_PUBLISH";
    }

    @Override
    public NodeOutcome execute(NodeExecutionContext ctx) {
        Map<String, Object> node = ctx.node();
        String pageIdStr = (String) node.get("page_id");
        if (pageIdStr == null || pageIdStr.isBlank()) {
            log.warn("[WIKI_PUBLISH] missing page_id in node config");
            return NodeOutcome.CONTINUE;
        }
        try {
            java.util.UUID pageId = java.util.UUID.fromString(pageIdStr);
            WikiPageEntity page = pageRepository.findById(pageId).orElse(null);
            if (page == null) {
                log.warn("[WIKI_PUBLISH] page not found: {}", pageId);
                return NodeOutcome.CONTINUE;
            }
            if (!"DRAFT".equals(page.getStatus())) {
                log.info("[WIKI_PUBLISH] page already published, skip: {}", pageId);
                return NodeOutcome.CONTINUE;
            }
            page.setStatus("PUBLISHED");
            page.setUpdatedBy(ctx.defaultAssignee());
            page.setUpdatedAt(java.time.Instant.now());
            pageRepository.save(page);
            log.info("[WIKI_PUBLISH] page published: {}", pageId);
            return NodeOutcome.CONTINUE;
        } catch (Exception e) {
            log.error("[WIKI_PUBLISH] failed: {}", e.getMessage());
            return NodeOutcome.FAILED;
        }
    }
}