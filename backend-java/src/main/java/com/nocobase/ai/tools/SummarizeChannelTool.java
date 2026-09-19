package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import com.nocobase.ai.AgentToolContext;
import com.nocobase.ai.AiAssistantService;
import com.nocobase.im.MessageService;
import com.nocobase.im.entity.ImMessageEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * summarize_channel 工具 — 接真：拉取频道最近消息并用 LLM 摘要。
 */
@Component
public class SummarizeChannelTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 50;

    private final MessageService messageService;
    private final AiAssistantService aiService;

    public SummarizeChannelTool(MessageService messageService, AiAssistantService aiService) {
        this.messageService = messageService;
        this.aiService = aiService;
    }

    @Override public String name() { return "summarize_channel"; }
    @Override public String description() { return "对指定频道的近期消息生成摘要"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AgentToolContext ctx) {
        UUID channelId = parseOptionalUuid(params, "channelId");
        if (channelId == null) channelId = ctx.channelId();
        if (channelId == null) return Map.of("error", "channelId 必填");
        int limit = parseIntOrDefault(params.get("limit"), DEFAULT_LIMIT);
        try {
            List<ImMessageEntity> messages = messageService.list(channelId, null, limit);
            String summary = generateSummary(messages);
            return Map.of("channelId", channelId.toString(), "messageCount", messages.size(),
                          "summary", summary);
        } catch (Exception e) {
            return Map.of("error", "摘要失败: " + e.getMessage());
        }
    }

    private String generateSummary(List<ImMessageEntity> messages) {
        if (messages.isEmpty()) return "该频道暂无消息";
        StringBuilder sb = new StringBuilder("近期消息摘要:\n");
        for (int i = 0; i < Math.min(messages.size(), 20); i++) {
            ImMessageEntity m = messages.get(i);
            sb.append("- [").append(m.getSenderId()).append("] ").append(m.getContent()).append("\n");
        }
        if (!aiService.isEnabled()) return sb.append("\n（LLM 未启用，以上为原始消息）").toString();
                    try {
                        Map<String, Object> result = aiService.askQuestion("请对以下消息列表做简要总结（3 句话以内）：",
                                sb.toString(), "");
                        Object data = result.get("data");
                        if (data instanceof Map<?, ?> m) {
                            Object answer = m.get("answer");
                            if (answer instanceof String s && !s.isBlank()) return s;
                        }
                        return data == null ? "（LLM 返回空）" : String.valueOf(data);
                    } catch (Exception e) {
                        return sb.append("\nLLM 摘要失败，以上为原始消息").toString();
                    }
    }

    private static UUID parseOptionalUuid(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        try { return UUID.fromString(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private static int parseIntOrDefault(Object v, int def) {
        if (v == null) return def;
        try { return Math.max(1, Math.min(200, Integer.parseInt(String.valueOf(v)))); }
        catch (Exception e) { return def; }
    }
}
