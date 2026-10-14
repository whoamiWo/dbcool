package com.nocobase.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PHASE105: WikiBlockService 测试（CRUD + 租户隔离 + toJsonString 各分支）。 */
class WikiBlockServiceTest {

    private WikiBlockRepository blockRepository;
    private WikiPageRepository pageRepository;
    private WikiBlockService service;

    private static final String TENANT = "t1";
    private static final String OTHER = "t2";
    private static final UUID PAGE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        blockRepository = mock(WikiBlockRepository.class);
        pageRepository = mock(WikiPageRepository.class);
        service = new WikiBlockService(blockRepository, pageRepository);
        when(blockRepository.save(any(WikiBlockEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private WikiPageEntity pageOf(String tenantId) {
        WikiPageEntity p = new WikiPageEntity();
        p.setId(PAGE_ID);
        p.setTenantId(tenantId);
        return p;
    }

    private WikiBlockEntity blockOf(String tenantId) {
        WikiBlockEntity b = new WikiBlockEntity();
        b.setId(UUID.randomUUID());
        b.setPageId(PAGE_ID);
        b.setTenantId(tenantId);
        b.setSortOrder(0);
        return b;
    }

    // ── createBlocksForPage ─────────────────────────────────
    @Test
    void createBlocksForPage_success_assignsSortOrder() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(TENANT)));

        List<WikiBlockEntity> out = service.createBlocksForPage(PAGE_ID,
                List.of(Map.of("type", "heading", "content", "标题"),
                        Map.of("type", "paragraph", "content", Map.of("text", "正文"))),
                TENANT, UUID.randomUUID());

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getSortOrder()).isEqualTo(0);
        assertThat(out.get(1).getSortOrder()).isEqualTo(1);
        verify(blockRepository).deleteByPageId(PAGE_ID);
    }

    @Test
    void createBlocksForPage_pageMissing_throws404() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createBlocksForPage(PAGE_ID, List.of(), TENANT, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void createBlocksForPage_tenantMismatch_throws403() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(OTHER)));

        assertThatThrownBy(() -> service.createBlocksForPage(PAGE_ID, List.of(), TENANT, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── toJsonString 各分支（经 createBlocksForPage 间接覆盖） ──
    @Test
    void createBlocksForPage_nullContent_becomesEmptyObject() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(TENANT)));

        List<WikiBlockEntity> out = service.createBlocksForPage(PAGE_ID,
                List.of(Map.of("type", "paragraph")), TENANT, UUID.randomUUID());

        assertThat(out.get(0).getContentJson()).isEqualTo("{}");
    }

    @Test
    void createBlocksForPage_plainStringContent_wrappedAsText() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(TENANT)));

        List<WikiBlockEntity> out = service.createBlocksForPage(PAGE_ID,
                List.of(Map.of("type", "paragraph", "content", "普通文本")), TENANT, UUID.randomUUID());

        assertThat(out.get(0).getContentJson()).contains("\"text\"").contains("普通文本");
    }

    @Test
    void createBlocksForPage_validJsonStringContent_preserved() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(TENANT)));

        List<WikiBlockEntity> out = service.createBlocksForPage(PAGE_ID,
                List.of(Map.of("type", "paragraph", "content", "{\"a\":1}")), TENANT, UUID.randomUUID());

        assertThat(out.get(0).getContentJson()).contains("\"a\"").contains("1");
    }

    @Test
    void createBlocksForPage_defaultType_isParagraph() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(TENANT)));

        List<WikiBlockEntity> out = service.createBlocksForPage(PAGE_ID,
                List.of(Map.of()), TENANT, UUID.randomUUID());

        assertThat(out.get(0).getType()).isEqualTo("paragraph");
    }

    // ── getBlocksByPageId ───────────────────────────────────
    @Test
    void getBlocksByPageId_delegatesToRepository() {
        List<WikiBlockEntity> expected = List.of(blockOf(TENANT));
        when(blockRepository.findByPageIdOrderBySortOrderAsc(PAGE_ID)).thenReturn(expected);

        assertThat(service.getBlocksByPageId(PAGE_ID)).isEqualTo(expected);
    }

    // ── moveBlock ───────────────────────────────────────────
    @Test
    void moveBlock_success_updatesParentAndOrder() {
        UUID blockId = UUID.randomUUID();
        WikiBlockEntity b = blockOf(TENANT);
        b.setId(blockId);
        when(blockRepository.findById(blockId)).thenReturn(Optional.of(b));

        UUID newParent = UUID.randomUUID();
        WikiBlockEntity out = service.moveBlock(blockId, newParent, 5, TENANT);

        assertThat(out.getParentId()).isEqualTo(newParent);
        assertThat(out.getSortOrder()).isEqualTo(5);
        assertThat(out.getUpdatedAt()).isNotNull();
    }

    @Test
    void moveBlock_nullArgs_keepsExisting() {
        UUID blockId = UUID.randomUUID();
        WikiBlockEntity b = blockOf(TENANT);
        b.setId(blockId);
        when(blockRepository.findById(blockId)).thenReturn(Optional.of(b));

        WikiBlockEntity out = service.moveBlock(blockId, null, null, TENANT);

        assertThat(out.getSortOrder()).isEqualTo(0); // 原值保留
    }

    @Test
    void moveBlock_missing_throws404() {
        when(blockRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveBlock(UUID.randomUUID(), null, 1, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void moveBlock_tenantMismatch_throws403() {
        UUID blockId = UUID.randomUUID();
        WikiBlockEntity b = blockOf(OTHER);
        b.setId(blockId);
        when(blockRepository.findById(blockId)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.moveBlock(blockId, null, 1, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── deleteBlocksByPageId ────────────────────────────────
    @Test
    void deleteBlocksByPageId_success_deletes() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(TENANT)));

        service.deleteBlocksByPageId(PAGE_ID, TENANT);

        verify(blockRepository).deleteByPageId(PAGE_ID);
    }

    @Test
    void deleteBlocksByPageId_pageMissing_throws404() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteBlocksByPageId(PAGE_ID, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deleteBlocksByPageId_tenantMismatch_throws403() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(OTHER)));

        assertThatThrownBy(() -> service.deleteBlocksByPageId(PAGE_ID, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(blockRepository, never()).deleteByPageId(any());
    }

    // ── reorderBlocks ───────────────────────────────────────
    @Test
    void reorderBlocks_success_reassignsOrder() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(TENANT)));
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();
        WikiBlockEntity e1 = blockOf(TENANT); e1.setId(b1);
        WikiBlockEntity e2 = blockOf(TENANT); e2.setId(b2);
        when(blockRepository.findById(b1)).thenReturn(Optional.of(e1));
        when(blockRepository.findById(b2)).thenReturn(Optional.of(e2));

        List<WikiBlockEntity> out = service.reorderBlocks(PAGE_ID, List.of(b1, b2), TENANT);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getSortOrder()).isEqualTo(0);
        assertThat(out.get(1).getSortOrder()).isEqualTo(1);
    }

    @Test
    void reorderBlocks_blockMissing_throws404() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(TENANT)));
        when(blockRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reorderBlocks(PAGE_ID, List.of(UUID.randomUUID()), TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void reorderBlocks_blockTenantMismatch_throws403() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(TENANT)));
        UUID b1 = UUID.randomUUID();
        WikiBlockEntity e1 = blockOf(OTHER); e1.setId(b1);
        when(blockRepository.findById(b1)).thenReturn(Optional.of(e1));

        assertThatThrownBy(() -> service.reorderBlocks(PAGE_ID, List.of(b1), TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void reorderBlocks_pageMissing_throws404() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reorderBlocks(PAGE_ID, List.of(), TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── batchUpsertBlocks ───────────────────────────────────
    @Test
    void batchUpsertBlocks_nullPageId_throws400() {
        assertThatThrownBy(() -> service.batchUpsertBlocks(null, List.of(Map.of()), TENANT, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void batchUpsertBlocks_nullBlocks_throws400() {
        assertThatThrownBy(() -> service.batchUpsertBlocks(PAGE_ID, null, TENANT, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void batchUpsertBlocks_emptyBlocks_throws400() {
        assertThatThrownBy(() -> service.batchUpsertBlocks(PAGE_ID, List.of(), TENANT, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void batchUpsertBlocks_success_deleteThenInsert() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(TENANT)));

        List<WikiBlockEntity> out = service.batchUpsertBlocks(PAGE_ID,
                List.of(Map.of("type", "paragraph", "content", "文本")), TENANT, UUID.randomUUID());

        assertThat(out).hasSize(1);
        verify(blockRepository).deleteByPageId(PAGE_ID);
    }

    @Test
    void batchUpsertBlocks_pageMissing_throws404() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.batchUpsertBlocks(PAGE_ID, List.of(Map.of()), TENANT, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void batchUpsertBlocks_tenantMismatch_throws403() {
        when(pageRepository.findById(PAGE_ID)).thenReturn(Optional.of(pageOf(OTHER)));

        assertThatThrownBy(() -> service.batchUpsertBlocks(PAGE_ID, List.of(Map.of()), TENANT, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
