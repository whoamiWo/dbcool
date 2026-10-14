package com.nocobase.ai;

import static org.junit.jupiter.api.Assertions.*;
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
}
