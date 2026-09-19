package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import com.nocobase.ai.AgentToolContext;
import com.nocobase.integration.dingtalk.DingTalkMessageService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * send_ding 工具 — 接真：委托 DingTalkMessageService 发送工作通知/群消息。
 *
 * <p>Phase 48 审计修复：删除硬编码 {@code sent:true}，改为真实调用钉钉 API。
 */
@Component
public class SendDingTool implements AgentTool {

    private final DingTalkMessageService dingTalkMessageService;

    public SendDingTool(DingTalkMessageService dingTalkMessageService) {
        this.dingTalkMessageService = dingTalkMessageService;
    }

    @Override public String name() { return "send_ding"; }
    @Override public String description() { return "发送钉钉工作通知或群消息"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AgentToolContext ctx) {
        boolean isGroup = "group".equals(params.get("type"));
        String to = (String) params.get("to");
        String chatId = (String) params.get("chatId");
        String title = (String) params.get("title");
        Map<String, Object> content = (Map<String, Object>) params.get("content");
        if (content == null) content = Map.of();
        if (isGroup) {
            if (chatId == null || chatId.isBlank()) {
                return Map.of("sent", false, "reason", "群消息需 chatId");
            }
            boolean sent = dingTalkMessageService.sendGroupMessage(chatId, title != null ? title : "通知", content);
            return Map.of("sent", sent, "chatId", chatId, "success", sent,
                          "reason", sent ? "ok" : "钉钉发送失败");
        } else {
            if (to == null || to.isBlank()) {
                return Map.of("sent", false, "reason", "工作通知需 recipient(to)");
            }
            boolean sent = dingTalkMessageService.sendWorkNotice("default", to,
                    title != null ? title : "通知", content);
            return Map.of("sent", sent, "to", to, "success", sent,
                          "reason", sent ? "ok" : "钉钉未配置或发送失败");
        }
    }
}
