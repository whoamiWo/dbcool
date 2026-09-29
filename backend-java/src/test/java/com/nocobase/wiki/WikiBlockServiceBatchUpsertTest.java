package com.nocobase.wiki;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WikiBlockServiceBatchUpsertTest {

    private WikiBlockRepository blockRepository;
    private WikiPageRepository pageRepository;
    private WikiBlockService wikiBlockService;

    @BeforeEach
    void setUp() {
        blockRepository = mock(WikiBlockRepository.class);
        pageRepository = mock(WikiPageRepository.class);
        wikiBlockService = new WikiBlockService(blockRepository, pageRepository);
    }

    @Test
    void batchUpsertBlocks_success() {
        UUID pageId = UUID.randomUUID();
        String tenantId = "test-tenant";
        UUID createdBy = UUID.randomUUID();

        WikiPageEntity page = new WikiPageEntity();
        page.setId(pageId);
        page.setTenantId(tenantId);
        when(pageRepository.findById(pageId)).thenReturn(java.util.Optional.of(page));
        doNothing().when(blockRepository).deleteByPageId(pageId);

        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> block1 = new HashMap<>();
        block1.put("type", "paragraph");
        block1.put("content", "纯文本内容");
        blocks.add(block1);

        Map<String, Object> block2 = new HashMap<>();
        block2.put("type", "heading");
        Map<String, Object> contentObj = new HashMap<>();
        contentObj.put("text", "标题内容");
        block2.put("content", contentObj);
        blocks.add(block2);

        when(blockRepository.save(any(WikiBlockEntity.class))).thenAnswer(invocation -> {
            WikiBlockEntity e = invocation.getArgument(0);
            assertNotNull(e.getId());
            assertEquals(pageId, e.getPageId());
            assertEquals(tenantId, e.getTenantId());
            assertNotNull(e.getContentJson());
            // 验证 content 是合法的 JSON
            assertTrue(e.getContentJson().startsWith("{") || e.getContentJson().equals("{}"));
            return e;
        });

        List<WikiBlockEntity> result = wikiBlockService.batchUpsertBlocks(pageId, blocks, tenantId, createdBy);

        assertEquals(2, result.size());
        // 断言两个 Block 的 sortOrder 分别为 0、1，且按序写入
        assertEquals(0, result.get(0).getSortOrder());
        assertEquals(1, result.get(1).getSortOrder());
        // 断言 content 均为合法 JSON（纯文本包成 {"text": "..."}，Map 序列化为 JSON 对象）
        assertEquals("{\"text\":\"纯文本内容\"}", result.get(0).getContentJson());
        assertEquals("{\"text\":\"标题内容\"}", result.get(1).getContentJson());
        // 断言递交给保存层的实体与结果一致（主路径 save 真的被调用了两次）
        verify(blockRepository, times(2)).save(any(WikiBlockEntity.class));
    }

    @Test
    void batchUpsertBlocks_emptyBlocks_throws400() {
        UUID pageId = UUID.randomUUID();
        String tenantId = "test-tenant";
        UUID createdBy = UUID.randomUUID();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
            wikiBlockService.batchUpsertBlocks(pageId, new ArrayList<>(), tenantId, createdBy)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("blocks"));
    }

    @Test
    void batchUpsertBlocks_pageNotFound_throws404() {
        UUID pageId = UUID.randomUUID();
        String tenantId = "test-tenant";
        UUID createdBy = UUID.randomUUID();

        when(pageRepository.findById(pageId)).thenReturn(java.util.Optional.empty());

        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> block = new HashMap<>();
        block.put("type", "paragraph");
        block.put("content", "test");
        blocks.add(block);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
            wikiBlockService.batchUpsertBlocks(pageId, blocks, tenantId, createdBy)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertTrue(ex.getReason().contains("页面不存在"));
    }

    @Test
    void batchUpsertBlocks_wrongTenant_throws403() {
        UUID pageId = UUID.randomUUID();
        String tenantId = "test-tenant";
        UUID createdBy = UUID.randomUUID();

        WikiPageEntity page = new WikiPageEntity();
        page.setId(pageId);
        page.setTenantId("other-tenant");
        when(pageRepository.findById(pageId)).thenReturn(java.util.Optional.of(page));

        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> block = new HashMap<>();
        block.put("type", "paragraph");
        block.put("content", "test");
        blocks.add(block);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
            wikiBlockService.batchUpsertBlocks(pageId, blocks, tenantId, createdBy)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("无权操作"));
    }
}