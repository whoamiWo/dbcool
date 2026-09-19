package com.nocobase.ai;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * AI Agent REST API。
 *
 * <p>端点：POST /api/ai/agent/execute
 */
@RestController
@RequestMapping("/api/ai/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal com.nocobase.auth.JwtAuthFilter.AuthenticatedUser user
    ) {
        String tenantId = user.tenantId();
        Object channelIdRaw = body.get("channelId");
        if (channelIdRaw == null || String.valueOf(channelIdRaw).isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 1, "message", "channelId 为必填字段"));
        }
        UUID channelId = UUID.fromString(String.valueOf(channelIdRaw));
        UUID userId = user.userId();
        String prompt = (String) body.getOrDefault("prompt", "");
        Map<String, Object> result = agentService.executeInChannel(tenantId, channelId, userId, prompt);
        return ResponseEntity.ok(result);
    }
}