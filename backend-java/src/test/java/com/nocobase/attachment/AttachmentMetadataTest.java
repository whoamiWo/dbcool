package com.nocobase.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttachmentMetadataTest {

    @Test
    void validMetadata_accepted() {
        AttachmentMetadata meta = new AttachmentMetadata(
                "orders/2026/file-001.pdf",
                "report.pdf",
                "application/pdf",
                12345L,
                Instant.now(),
                UUID.randomUUID(),
                Map.of());

        assertThat(meta.storageKey()).isEqualTo("orders/2026/file-001.pdf");
        assertThat(meta.size()).isEqualTo(12345L);
    }

    @Test
    void blankStorageKey_rejected() {
        assertThatThrownBy(() -> new AttachmentMetadata(
                "", "x.pdf", "application/pdf", 1L, Instant.now(), UUID.randomUUID(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storageKey");
    }

    @Test
    void negativeSize_rejected() {
        assertThatThrownBy(() -> new AttachmentMetadata(
                "k", "x.pdf", "application/pdf", -1L, Instant.now(), UUID.randomUUID(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
    }

    @Test
    void isValid_mapWithStorageKey_returnsTrue() {
        assertThat(AttachmentMetadata.isValid(Map.of(
                "storageKey", "k", "originalName", "x"))).isTrue();
    }

    @Test
    void isValid_mapWithoutStorageKey_returnsFalse() {
        assertThat(AttachmentMetadata.isValid(Map.of("originalName", "x"))).isFalse();
    }

    @Test
    void isValid_null_returnsFalse() {
        assertThat(AttachmentMetadata.isValid(null)).isFalse();
    }

    @Test
    void isValid_attachmentInstance_returnsTrue() {
        AttachmentMetadata meta = new AttachmentMetadata(
                "k", "x.pdf", "application/pdf", 1L, Instant.now(), UUID.randomUUID(), Map.of());
        assertThat(AttachmentMetadata.isValid(meta)).isTrue();
    }
}
