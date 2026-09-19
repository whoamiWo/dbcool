package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * send_ding 工具 — 发送钉钉通知(占位,接入钉钉 SDK 后接真)。
 */
@Component
public class SendDingTool implements AgentTool {
    @Override public String name() { return "send_ding"; }
    @Override public String description() { return "发送钉钉通知"; }
    @Override public Map<String, Object> execute(Map<String, Object> params) {
        return Map.of("sent", true, "to", params.get("to"));
    }
}
