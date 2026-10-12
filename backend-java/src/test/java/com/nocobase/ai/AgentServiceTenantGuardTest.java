package com.nocobase.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.tenant.TenantContext;
import com.nocobase.wiki.WikiPageService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * R5-1: AI 会话租户隔离单元测试。
 *
 * <p>验证 {@code AgentService} 的会话查询使用显式 tenantId 参数,而不是 ThreadLocal。
 */
class AgentServiceTenantGuardTest {

    private AiAgentEntityRepository agentRepo;
    private AiConversationEntityRepository conversationRepo;
    private AgentService service;

    private final UUID agentId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentRepo = mock(AiAgentEntityRepository.class);
        conversationRepo = mock(AiConversationEntityRepository.class);
        service = new AgentService(agentRepo, conversationRepo,
                mock(AgentToolRegistry.class),
                mock(AiAssistantService.class),
                mock(WikiPageService.class),
                new ObjectMapper());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void findByTenant_usesExplicitTenantId_notThreadLocal() throws Exception {
        var agentA = new AiAgentEntity();
        agentA.setId(agentId);
        agentA.setTenantId("tenant_a");
        agentA.setChannelId(channelId);
        agentA.setStatus(AiAgentEntity.Status.ACTIVE);

        var convA = new AiConversationEntity();
        convA.setId(UUID.randomUUID());
        convA.setTenantId("tenant_a");
        convA.setAgentId(agentId);
        convA.setChannelId(channelId);
        convA.setUserId(userId);

        when(agentRepo.findByTenantIdAndChannelId("tenant_a", channelId))
                .thenReturn(java.util.List.of(agentA));
        when(conversationRepo.findByAgentIdAndChannelIdAndUserIdAndTenantId(
                agentId, channelId, userId, "tenant_a"))
                .thenReturn(Optional.of(convA));

        TenantContext.set("tenant_b"); // ThreadLocal 故意设成其他租户

        var method = AgentService.class.getDeclaredMethod("ensureConversation",
                AiAgentEntity.class, UUID.class, UUID.class, String.class);
        method.setAccessible(true);
        var result = method.invoke(service, agentA, channelId, userId, "tenant_a");

        assertThat(result).isNotNull();
        verify(conversationRepo).findByAgentIdAndChannelIdAndUserIdAndTenantId(
                agentId, channelId, userId, "tenant_a");
        assertThat(TenantContext.currentTenantId()).isEqualTo("tenant_b");
    }
}