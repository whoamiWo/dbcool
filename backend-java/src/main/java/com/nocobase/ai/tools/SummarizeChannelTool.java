package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * summarize_channel 工具 — 摘要频道消息(占位,接入 IM 服务后接真)。
 */
@Component
public class SummarizeChannelTool implements AgentTool {
    @Override public String name() { return "summarize_channel"; }
    @Override public String description() { return "摘要指定频道的近期消息"; }
    @Override public Map<String, Object> execute(Map<String, Object> params) {
        return Map.of("summary", "TODO:summarize_channel(" + params.get("channelId") + ")");
    }
}
