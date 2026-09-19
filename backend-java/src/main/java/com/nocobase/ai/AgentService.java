package com.nocobase.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.wiki.WikiPageEntity;
import com.nocobase.wiki.WikiPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * AI Agent 多步执行服务（Phase 48 F3 接真）。
 *
 * <p>接收频道消息/对话，通过 {@link AiAssistantService}（OpenAI 兼容网关）驱动 ReAct 循环：
 * <ol>
 *   <li>把上下文 + 工具描述注入 prompt</li>
 *   <li>让 LLM 返回 <pre>{@code { "tool": "name", "params": {...} }}</pre></li>
 *   <li>{@link AgentToolRegistry} 查到对应工具并执行,观察结果回填</li>
 *   <li>达到最大步数或无工具调用时停止</li>
 * </ol>
 * 删除旧版占位拼接字符串,改为真实工具执行。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int MAX_STEPS = 6;
    private static final String TOOL_INSTRUCTIONS = """
            ## Available tools
            You MUST respond with a JSON object using ONE of these tools:
            ```json
            {"tool": "<name>", "params": {...}}
            ```
            Or reply with plain text when no tool is needed.
            """;

    private final AiAgentEntityRepository agentRepo;
    private final AiConversationEntityRepository conversationRepo;
    private final AgentToolRegistry toolRegistry;
    private final AiAssistantService aiService;
    private final WikiPageService wikiPageService;
    private final ObjectMapper mapper;

    public AgentService(AiAgentEntityRepository agentRepo,
                        AiConversationEntityRepository conversationRepo,
                        AgentToolRegistry toolRegistry,
                        AiAssistantService aiService,
                        WikiPageService wikiPageService,
                        ObjectMapper mapper) {
        this.agentRepo = agentRepo;
        this.conversationRepo = conversationRepo;
        this.toolRegistry = toolRegistry;
        this.aiService = aiService;
        this.wikiPageService = wikiPageService;
        this.mapper = mapper;
    }

    /**
     * 执行单轮对话：解析用户 prompt，ReAct 循环获取工具结果，最终回答用户。
     *
     * @return 返回对话摘要结果
     */
    @Transactional
    public Map<String, Object> executeInChannel(String tenantId, UUID channelId,
                                                 UUID userId, String prompt) {
        // channelId 缺失时直接 400,不再 500
        if (channelId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "channelId 为必填字段");
        }
        // 1) 查找频道绑定的 Agent
        AiAgentEntity agent = findAgent(tenantId, channelId);
        if (agent == null) {
            return Map.of("code", 0, "message", "AI Agent 未启用或未配置",
                    "data", Map.of("available", false));
        }
        // 2) 会话管理
        AiConversationEntity conv = ensureConversation(agent, channelId, userId);
        List<Map<String, Object>> messages = parseMessages(conv.getMessagesJson());
        messages.add(Map.of("role", "user", "content", prompt));

        // 3) ReAct 循环
        List<String> executedTools = new ArrayList<>();
        String observation = "";
        for (int step = 0; step < MAX_STEPS; step++) {
            String context = buildContext(messages, executedTools, observation);
            String response = callLlm(context, getBearerToken());
            Map<String, Object> decision = parseToolDecision(response);
            if (decision == null) {
                // LLM 已给出最终文本答案
                break;
            }
            String toolName = (String) decision.get("tool");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) decision.getOrDefault("params", Map.of());
            Map<String, Object> result = executeTool(agent, toolName, params, tenantId);
            executedTools.add(toolName + "(" + safeToString(params) + ")");
            observation = safeToString(result);
            messages.add(Map.of("role", "assistant", "content",
                    "Called tool: " + toolName + ", result: " + observation));
        }

        // 4) 更新上下文
        messages.add(Map.of("role", "assistant", "content", observation));
        conv.setMessagesJson(toJson(messages));
        conv.setUpdatedAt(Instant.now());
        conversationRepo.save(conv);

        return Map.of("code", 0, "message", "success", "data", Map.of(
                "agentName", agent.getName(),
                "result", observation,
                "conversationId", conv.getId().toString(),
                "executedTools", executedTools
        ));
    }

    // -------------------- 内部 --------------------

    private AiAgentEntity findAgent(String tenantId, UUID channelId) {
        return agentRepo.findByTenantIdAndChannelId(tenantId, channelId)
                .stream().filter(a -> a.getStatus() == AiAgentEntity.Status.ACTIVE)
                .findFirst().orElse(null);
    }

    private AiConversationEntity ensureConversation(AiAgentEntity agent,
                                                     UUID channelId, UUID userId) {
        return conversationRepo.findByAgentIdAndChannelIdAndUserId(
                        agent.getId(), channelId, userId)
                .stream().findFirst()
                .orElseGet(() -> {
                    AiConversationEntity c = new AiConversationEntity();
                    c.setId(UUID.randomUUID());
                    c.setTenantId(agent.getTenantId());
                    c.setAgentId(agent.getId());
                    c.setChannelId(channelId);
                    c.setUserId(userId);
                    c.setMessagesJson("[]");
                    c.setCreatedAt(Instant.now());
                    c.setUpdatedAt(Instant.now());
                    return conversationRepo.save(c);
                });
    }

    private String buildContext(List<Map<String, Object>> messages,
                                 List<String> executedTools, String lastObservation) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an enterprise AI assistant. Use the tools below to help the user.\n\n")
          .append(TOOL_INSTRUCTIONS).append("\n\n");
        if (!executedTools.isEmpty()) {
            sb.append("Tools already called: ").append(String.join(", ", executedTools)).append("\n");
        }
        if (!lastObservation.isBlank()) {
            sb.append("Last observation: ").append(lastObservation).append("\n");
        }
        for (Map<String, Object> m : messages) {
            if (m.containsKey("role") && m.containsKey("content")) {
                sb.append(m.get("role")).append(": ").append(m.get("content")).append("\n");
            }
        }
        return sb.toString();
    }

    private String callLlm(String context, String bearerToken) {
        if (!aiService.isEnabled()) {
            return "AI 服务未启用";
        }
        try {
            var result = aiService.askQuestion("请判断下一步应调用哪个工具,若无可用工具则直接回答问题,只输出 JSON:",
                    context, bearerToken);
            Object data = result.get("data");
            if (data instanceof Map<?, ?> m) {
                Object answer = m.get("answer");
                if (answer instanceof String s) return s;
            }
            return String.valueOf(data == null ? "无响应" : data);
        } catch (Exception e) {
            log.warn("[Agent] LLM 调用失败: {}", e.getMessage());
            return "LLM 服务异常,无法获取决策";
        }
    }

    private String getBearerToken() {
        // 从 SecurityContext 取 JWT,空字符串表示匿名(网关会降级)
        try {
            org.springframework.security.core.context.SecurityContext ctx =
                    org.springframework.security.core.context.SecurityContextHolder.getContext();
            var auth = ctx.getAuthentication();
            if (auth != null && auth.getCredentials() instanceof String token) {
                return token;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToolDecision(String response) {
        if (response == null) return null;
        String text = response.trim();
        // 提取 JSON 对象(可能包裹在 ```json ... ``` 中)
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start) return null;
        String json = text.substring(start, end + 1);
        try {
            Map<String, Object> d = mapper.readValue(json, new TypeReference<>() {});
            if (d.get("tool") == null) return null;
            if (!(d.get("tool") instanceof String)) return null;
            if (d.get("params") == null) d.put("params", Map.of());
            return d;
        } catch (Exception e) {
            log.debug("[Agent] 解析工具决策失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> executeTool(AiAgentEntity agent, String toolName,
                                             Map<String, Object> params, String tenantId) {
        AgentTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            return Map.of("error", "工具不存在: " + toolName);
        }
        try {
            List<String> allowed = parseAllowedTools(agent.getAllowedToolsJson());
            if (!allowed.contains(toolName) && !allowed.isEmpty()) {
                return Map.of("error", "工具不在允许列表: " + toolName);
            }
            return tool.execute(params);
        } catch (Exception e) {
            log.warn("[Agent] 工具 {} 执行异常: {}", toolName, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    private List<String> parseAllowedTools(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            // 兜底硬编码名与历史保持一致
            return List.of("search_wiki", "create_task", "create_page",
                    "summarize_channel", "send_ding");
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of("search_wiki", "create_task", "create_page",
                    "summarize_channel", "send_ding");
        }
    }

    private List<Map<String, Object>> parseMessages(String json) {
        if (json == null || "[]".equals(json)) return new ArrayList<>();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String safeToString(Object o) {
        if (o == null) return "";
        if (o instanceof String s) return s;
        try {
            return new ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
