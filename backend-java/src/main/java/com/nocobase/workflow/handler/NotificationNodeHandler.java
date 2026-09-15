package com.nocobase.workflow.handler;

import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowNodeHandler;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NotificationNodeHandler implements WorkflowNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationNodeHandler.class);

    @Override
    public String type() {
        return "NOTIFICATION";
    }

    @Override
    public NodeOutcome execute(NodeExecutionContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) ctx.node().getOrDefault("config", Map.of());
        String title = (String) config.getOrDefault("title", "通知");
        String body = (String) config.getOrDefault("body", config.getOrDefault("message", ""));

        log.info("[workflow {} node {}] NOTIFICATION {} - {}",
                ctx.instance().getId(), ctx.node().get("id"), title, body);

        // Week 41 仅日志 — Week 42+ 接入 NotificationService / 站内信 / 邮件
        // 原 logNotification() 行为保留,但走 handler 策略化
        return NodeOutcome.CONTINUE;
    }
}
