package com.nocobase.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PHASE105: AI 实体 POJO 覆盖（AiConversationEntity / AiAgentEntity）。 */
class AiEntityPojoTest {

    @Test
    void conversation_allFields() {
        AiConversationEntity e = new AiConversationEntity();
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        e.setId(id);
        e.setTenantId("t1");
        e.setAgentId(agentId);
        e.setChannelId(channelId);
        e.setUserId(userId);
        e.setMessagesJson("[{\"role\":\"user\"}]");
        e.setCreatedAt(now);
        e.setUpdatedAt(now);

        assertThat(e.getId()).isEqualTo(id);
        assertThat(e.getTenantId()).isEqualTo("t1");
        assertThat(e.getAgentId()).isEqualTo(agentId);
        assertThat(e.getChannelId()).isEqualTo(channelId);
        assertThat(e.getUserId()).isEqualTo(userId);
        assertThat(e.getMessagesJson()).isEqualTo("[{\"role\":\"user\"}]");
        assertThat(e.getCreatedAt()).isEqualTo(now);
        assertThat(e.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void agent_allFields() {
        AiAgentEntity e = new AiAgentEntity();
        UUID id = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        Instant now = Instant.now();

        e.setId(id);
        e.setTenantId("t1");
        e.setChannelId(channelId);
        e.setName("客服助手");
        e.setDescription("自动应答");
        e.setStatus(AiAgentEntity.Status.ACTIVE);
        e.setAllowedToolsJson("[\"search\"]");
        e.setCreatedAt(now);

        assertThat(e.getId()).isEqualTo(id);
        assertThat(e.getTenantId()).isEqualTo("t1");
        assertThat(e.getChannelId()).isEqualTo(channelId);
        assertThat(e.getName()).isEqualTo("客服助手");
        assertThat(e.getDescription()).isEqualTo("自动应答");
        assertThat(e.getStatus()).isEqualTo(AiAgentEntity.Status.ACTIVE);
        assertThat(e.getAllowedToolsJson()).isEqualTo("[\"search\"]");
        assertThat(e.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void agent_defaultStatus_isActive() {
        AiAgentEntity e = new AiAgentEntity();
        assertThat(e.getStatus()).isEqualTo(AiAgentEntity.Status.ACTIVE);
    }

    @Test
    void agent_statusEnum_hasActiveAndPaused() {
        assertThat(AiAgentEntity.Status.values())
                .containsExactlyInAnyOrder(AiAgentEntity.Status.ACTIVE, AiAgentEntity.Status.PAUSED);
    }
}
