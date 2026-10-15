package com.nocobase.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nocobase.wiki.WikiPageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE105: AgentService 覆盖（mock 所有依赖，覆盖分支）。
 */
public class AgentServiceCoverageTest {

    private AiAgentEntityRepository agentRepo;
    private AiConversationEntityRepository conversationRepo;
    private AgentToolRegistry toolRegistry;
    private AiAssistantService aiService;
    private WikiPageService wikiPageService;
    private AgentService service;

    private static final String TENANT = "tenant_default";
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentRepo = mock(AiAgentEntityRepository.class);
        conversationRepo = mock(AiConversationEntityRepository.class);
        toolRegistry = mock(AgentToolRegistry.class);
        aiService = mock(AiAssistantService.class);
        wikiPageService = mock(WikiPageService.class);

        service = new AgentService(agentRepo, conversationRepo, toolRegistry,
                aiService, wikiPageService, new ObjectMapper());
    }

    @Test
    void channelIdNull_throws400() {
        assertThrows(ResponseStatusException.class,
                () -> service.executeInChannel(TENANT, null, USER_ID, "hi"));
    }

    @Test
    void agentNotFound_returnsNotAvailable() {
        when(agentRepo.findByTenantIdAndChannelId(TENANT, CHANNEL_ID))
                .thenReturn(List.of());
        Map<String, Object> r = service.executeInChannel(TENANT, CHANNEL_ID, USER_ID, "hi");
        assertEquals(0, r.get("code"));
        assertFalse((Boolean) ((Map<String,Object>) r.get("data")).get("available"));
    }

    @Test
    void agentInactive_returnsNotAvailable() {
        AiAgentEntity agent = new AiAgentEntity();
        agent.setId(AGENT_ID);
        agent.setName("bot");
        agent.setStatus(AiAgentEntity.Status.PAUSED);
        agent.setTenantId(TENANT);
        agent.setChannelId(CHANNEL_ID);
        when(agentRepo.findByTenantIdAndChannelId(TENANT, CHANNEL_ID))
                .thenReturn(List.of(agent));
        Map<String, Object> r = service.executeInChannel(TENANT, CHANNEL_ID, USER_ID, "hi");
        assertFalse((Boolean) ((Map<String,Object>) r.get("data")).get("available"));
    }

    @Test
    void reactLoop_toolDecision_thenFinalAnswer() throws Exception {
        // 1) Agent 存在且 ACTIVE
        AiAgentEntity agent = new AiAgentEntity();
        agent.setId(AGENT_ID);
        agent.setName("bot");
        agent.setStatus(AiAgentEntity.Status.ACTIVE);
        agent.setTenantId(TENANT);
        agent.setChannelId(CHANNEL_ID);
        agent.setAllowedToolsJson("[\"search_wiki\"]");
        when(agentRepo.findByTenantIdAndChannelId(TENANT, CHANNEL_ID)).thenReturn(List.of(agent));

        // 2) Conversation 已存在
        AiConversationEntity conv = new AiConversationEntity();
        conv.setId(UUID.randomUUID());
        conv.setAgentId(AGENT_ID);
        conv.setChannelId(CHANNEL_ID);
        conv.setUserId(USER_ID);
        conv.setTenantId(TENANT);
        conv.setMessagesJson("[]");
        when(conversationRepo.findByAgentIdAndChannelIdAndUserIdAndTenantId(any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(conv));
        when(conversationRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        // 3) aiService: 先返回工具决策，第二次返回最终答案
        when(aiService.isEnabled()).thenReturn(true);
        when(aiService.askQuestion(any(), any(), any()))
                .thenReturn(Map.of("data", Map.of("answer",
                        "{\"tool\":\"search_wiki\",\"params\":{\"q\":\"abc\"}}")))
                .thenReturn(Map.of("data", Map.of("answer", "最终答案")));

        // 4) toolRegistry 返回可用工具
        AgentTool tool = mock(AgentTool.class);
        when(toolRegistry.get("search_wiki")).thenReturn(tool);
        when(tool.execute(any(), any())).thenReturn(Map.of("result", "wiki data"));

        Map<String, Object> resp = service.executeInChannel(TENANT, CHANNEL_ID, USER_ID, "hi");

        assertEquals(0, resp.get("code"));
        assertEquals("bot", ((Map<String,Object>)resp.get("data")).get("agentName"));
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) ((Map<String,Object>)resp.get("data")).get("executedTools");
        assertFalse(tools.isEmpty());
    }

    @Test
    void parseToolDecision_jsonWithoutTool_returnsNull() throws Exception {
        // 反射调用私有方法
        var method = AgentService.class.getDeclaredMethod("parseToolDecision", String.class);
        method.setAccessible(true);
        Object result = method.invoke(service, "{\"notool\":1}");
        assertNull(result);
    }

    @Test
    void executeTool_toolNotAllowed_returnsError() throws Exception {
        AiAgentEntity agent = new AiAgentEntity();
        agent.setAllowedToolsJson("[\"other_tool\"]");
        AgentTool tool = mock(AgentTool.class);
        when(toolRegistry.get("search_wiki")).thenReturn(tool);

        var method = AgentService.class.getDeclaredMethod("executeTool",
                AiAgentEntity.class, String.class, Map.class, String.class, UUID.class, UUID.class);
        method.setAccessible(true);
        Object result = method.invoke(service, agent, "search_wiki", Map.of(), TENANT, USER_ID, CHANNEL_ID);

        assertTrue(((Map<String,Object>)result).containsKey("error"));
    }

    @Test
    void getBearerToken_credentialsIsString_returnsToken() throws Exception {
        org.springframework.security.core.context.SecurityContext ctx =
                org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "user", "valid-token");
        ctx.setAuthentication(auth);
        org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);

        var method = AgentService.class.getDeclaredMethod("getBearerToken");
        method.setAccessible(true);
        String token = (String) method.invoke(service);

        assertEquals("valid-token", token);
    }
}
