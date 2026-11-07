package com.nocobase.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** V81/R7：消息实体字段映射。 */
class MessageEntityTest {

    @Test
    void fields_roundTrip() {
        MessageEntity message = new MessageEntity();
        UUID id = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        Instant eventAt = Instant.parse("2026-11-06T00:00:00Z");

        message.setId(id);
        message.setSender(sender);
        message.setRecipient(recipient);
        message.setType("WORKFLOW_NOTICE");
        message.setTitle("通知");
        message.setBody("正文");
        message.setRelatedId("related");
        message.setRead(true);
        message.setCreatedAt(eventAt);
        message.setTenantId("tenant_a");
        message.setDeeplink("/designer/instances/1");
        message.setReadAt(eventAt);
        message.setDigestKey("digest");
        message.setPriority("HIGH");
        message.setChannelSentJson("{}");
        message.setDigestCount(2);
        message.setLastEventAt(eventAt);

        assertThat(message.getId()).isEqualTo(id);
        assertThat(message.getSender()).isEqualTo(sender);
        assertThat(message.getRecipient()).isEqualTo(recipient);
        assertThat(message.getType()).isEqualTo("WORKFLOW_NOTICE");
        assertThat(message.getTitle()).isEqualTo("通知");
        assertThat(message.getBody()).isEqualTo("正文");
        assertThat(message.getRelatedId()).isEqualTo("related");
        assertThat(message.isRead()).isTrue();
        assertThat(message.getCreatedAt()).isEqualTo(eventAt);
        assertThat(message.getTenantId()).isEqualTo("tenant_a");
        assertThat(message.getDeeplink()).isEqualTo("/designer/instances/1");
        assertThat(message.getReadAt()).isEqualTo(eventAt);
        assertThat(message.getDigestKey()).isEqualTo("digest");
        assertThat(message.getPriority()).isEqualTo("HIGH");
        assertThat(message.getChannelSentJson()).isEqualTo("{}");
        assertThat(message.getDigestCount()).isEqualTo(2);
        assertThat(message.getLastEventAt()).isEqualTo(eventAt);
    }
}
