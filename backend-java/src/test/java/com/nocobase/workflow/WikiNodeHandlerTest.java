package com.nocobase.workflow;

import com.nocobase.workflow.handler.WikiPublishNodeHandler;
import com.nocobase.workflow.handler.WikiArchiveNodeHandler;
import com.nocobase.wiki.WikiPageEntity;
import com.nocobase.wiki.WikiPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Wiki 工作流节点处理器测试。
 */
class WikiNodeHandlerTest {

    private WikiPageRepository pageRepository;
    private WikiPublishNodeHandler publishHandler;
    private WikiArchiveNodeHandler archiveHandler;

    @BeforeEach
    void setUp() {
        pageRepository = Mockito.mock(WikiPageRepository.class);
        publishHandler = new WikiPublishNodeHandler(pageRepository);
        archiveHandler = new WikiArchiveNodeHandler(pageRepository);
    }

    @Test
    void publishNode_shouldSetStatusToPublished() {
        var page = new WikiPageEntity();
        page.setId(UUID.randomUUID());
        page.setStatus("DRAFT");
        when(pageRepository.findById(any())).thenReturn(java.util.Optional.of(page));

        var ctx = new NodeExecutionContext(
                null,
                Map.of("page_id", page.getId().toString()),
                null,
                null
        );

        var outcome = publishHandler.execute(ctx);
        assertEquals(NodeOutcome.CONTINUE, outcome);
        assertEquals("PUBLISHED", page.getStatus());
    }

    @Test
    void archiveNode_shouldSetStatusToArchived() {
        var page = new WikiPageEntity();
        page.setId(UUID.randomUUID());
        page.setStatus("PUBLISHED");
        when(pageRepository.findById(any())).thenReturn(java.util.Optional.of(page));

        var ctx = new NodeExecutionContext(
                null,
                Map.of("page_id", page.getId().toString()),
                null,
                null
        );

        var outcome = archiveHandler.execute(ctx);
        assertEquals(NodeOutcome.CONTINUE, outcome);
        assertEquals("ARCHIVED", page.getStatus());
    }

    @Test
    void publishNode_shouldSkipWhenPageNotFound() {
        when(pageRepository.findById(any())).thenReturn(java.util.Optional.empty());

        var ctx = new NodeExecutionContext(
                null,
                Map.of("page_id", UUID.randomUUID().toString()),
                null,
                null
        );

        var outcome = publishHandler.execute(ctx);
        assertEquals(NodeOutcome.CONTINUE, outcome);
    }
}
