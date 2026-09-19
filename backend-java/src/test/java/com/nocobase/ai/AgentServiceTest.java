package com.nocobase.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.wiki.WikiPageService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** AgentService / AgentController 测试(Phase 48 F3 接真)。 */
class AgentServiceTest {

    private AiAgentEntityRepository agentRepo;
    private AiConversationEntityRepository conversationRepo;
    private AgentToolRegistry toolRegistry;
    private AiAssistantService aiService;
    private WikiPageService wikiPageService;
    private AgentService service;
    private AgentController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();
    private final AuthenticatedUser user = new AuthenticatedUser(userId, "bob", "tenant_default");

    @BeforeEach
    void setUp() {
        agentRepo = mock(AiAgentEntityRepository.class);
        conversationRepo = mock(AiConversationEntityRepository.class);
        toolRegistry = mock(AgentToolRegistry.class);
        aiService = mock(AiAssistantService.class);
        wikiPageService = mock(WikiPageService.class);
        service = new AgentService(agentRepo, conversationRepo, toolRegistry,
                aiService, wikiPageService, new ObjectMapper());
        controller = new AgentController(service);
    }

    private AiAgentEntity agent() {
        AiAgentEntity a = new AiAgentEntity();
        a.setId(UUID.randomUUID());
        a.setTenantId("tenant_default");
        a.setChannelId(channelId);
        a.setStatus(AiAgentEntity.Status.ACTIVE);
        a.setName("测试Agent");
        a.setAllowedToolsJson("[]");
        return a;
    }

    @Test
    void execute_nullChannelId_returns400() {
        assertThatThrownBy(() ->
                service.executeInChannel("tenant_default", null, userId, "hi"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e ->
                        assertThat(((ResponseStatusException) e).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void execute_noAgent_returnsNotAvailable() {
        when(agentRepo.findByTenantIdAndChannelId("tenant_default", channelId))
                .thenReturn(List.of());
        var result = service.executeInChannel("tenant_default", channelId, userId, "hi");
        // 返回包含 available=false 的 data
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data.get("available")).isEqualTo(false);
        // 无 agent 时不应该调用 LLM
        verify(aiService, never()).askQuestion(any(), any(), any());
    }

    @Test
    void execute_disabledLlmReturnsFallback() {
        AiAgentEntity a = agent();
        when(agentRepo.findByTenantIdAndChannelId("tenant_default", channelId))
                .thenReturn(List.of(a));
        when(conversationRepo.findByAgentIdAndChannelIdAndUserId(a.getId(), channelId, userId))
                .thenReturn(List.of());
        var conv = new AiConversationEntity();
        conv.setId(UUID.randomUUID());
        conv.setMessagesJson("[]");
        when(conversationRepo.save(any())).thenReturn(conv);
        when(aiService.isEnabled()).thenReturn(false);
        var result = service.executeInChannel("tenant_default", channelId, userId, "查询一下");
        assertThat(result.get("code")).isEqualTo(0);
        assertThat(result.get("message")).isEqualTo("success");
    }

    @Test
    void controller_nullChannelId_returns400() {
        var resp = controller.execute(Map.of("prompt", "hi"), user);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void controller_validChannelIdDelegates() {
        AiAgentEntity a = agent();
        when(agentRepo.findByTenantIdAndChannelId(eq("tenant_default"), eq(channelId)))
                .thenReturn(List.of());
        var resp = controller.execute(Map.of(
                "channelId", channelId.toString(),
                "prompt", "hi"), user);
        // 无 agent 走 fallback
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("available"))
                .isEqualTo(false);
    }
}
