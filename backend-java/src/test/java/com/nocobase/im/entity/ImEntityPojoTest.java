package com.nocobase.im.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PHASE105: IM 实体 POJO 覆盖（ImAttachment / ImPin / ImMessageRead）。 */
class ImEntityPojoTest {

    @Test
    void attachment_allFields() {
        ImAttachmentEntity e = new ImAttachmentEntity();
        UUID id = UUID.randomUUID();
        UUID msgId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID uploadedBy = UUID.randomUUID();
        Instant now = Instant.now();

        e.setId(id);
        e.setMessageId(msgId);
        e.setChannelId(channelId);
        e.setStorageKey("t1/msg/a.pdf");
        e.setFileName("a.pdf");
        e.setContentType("application/pdf");
        e.setSizeBytes(2048L);
        e.setUploadedBy(uploadedBy);
        e.setCreatedAt(now);
        e.setTenantId("t1");

        assertThat(e.getId()).isEqualTo(id);
        assertThat(e.getMessageId()).isEqualTo(msgId);
        assertThat(e.getChannelId()).isEqualTo(channelId);
        assertThat(e.getStorageKey()).isEqualTo("t1/msg/a.pdf");
        assertThat(e.getFileName()).isEqualTo("a.pdf");
        assertThat(e.getContentType()).isEqualTo("application/pdf");
        assertThat(e.getSizeBytes()).isEqualTo(2048L);
        assertThat(e.getUploadedBy()).isEqualTo(uploadedBy);
        assertThat(e.getCreatedAt()).isEqualTo(now);
        assertThat(e.getTenantId()).isEqualTo("t1");
    }

    @Test
    void pin_allFields() {
        ImPinEntity e = new ImPinEntity();
        UUID id = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID msgId = UUID.randomUUID();
        UUID pinnedBy = UUID.randomUUID();
        Instant now = Instant.now();

        e.setId(id);
        e.setTenantId("t1");
        e.setChannelId(channelId);
        e.setMessageId(msgId);
        e.setPinnedBy(pinnedBy);
        e.setPinnedAt(now);
        e.setUnpinnedAt(null);

        assertThat(e.getId()).isEqualTo(id);
        assertThat(e.getTenantId()).isEqualTo("t1");
        assertThat(e.getChannelId()).isEqualTo(channelId);
        assertThat(e.getMessageId()).isEqualTo(msgId);
        assertThat(e.getPinnedBy()).isEqualTo(pinnedBy);
        assertThat(e.getPinnedAt()).isEqualTo(now);
        assertThat(e.getUnpinnedAt()).isNull();
    }

    @Test
    void pin_statusEnum_values() {
        assertThat(ImPinEntity.Status.values()).isNotEmpty();
    }

    @Test
    void messageRead_allFields() {
        ImMessageReadEntity e = new ImMessageReadEntity();
        UUID id = UUID.randomUUID();
        UUID msgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        e.setId(id);
        e.setMessageId(msgId);
        e.setUserId(userId);
        e.setReadAt(now);
        e.setTenantId("t1");

        assertThat(e.getId()).isEqualTo(id);
        assertThat(e.getMessageId()).isEqualTo(msgId);
        assertThat(e.getUserId()).isEqualTo(userId);
        assertThat(e.getReadAt()).isEqualTo(now);
        assertThat(e.getTenantId()).isEqualTo("t1");
    }
}
