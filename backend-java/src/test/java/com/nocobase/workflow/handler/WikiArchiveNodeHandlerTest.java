package com.nocobase.workflow.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nocobase.wiki.WikiPageEntity;
import com.nocobase.wiki.WikiPageRepository;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;

class WikiArchiveNodeHandlerTest {

    private final WikiPageRepository pageRepository = mock(WikiPageRepository.class);
    private final WikiArchiveNodeHandler handler = new WikiArchiveNodeHandler(pageRepository);

    @Test
    void missingPageId_returnsContinue() {
        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.node()).thenReturn(Map.of("page_id", ""));

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.CONTINUE, outcome);
        verify(pageRepository, never()).findById(any());
    }

    @Test
    void blankPageId_returnsContinue() {
        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.node()).thenReturn(Map.of("page_id", "   "));

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.CONTINUE, outcome);
        verify(pageRepository, never()).findById(any());
    }

    @Test
    void pageNotFound_returnsContinue() {
        UUID pageId = UUID.randomUUID();
        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.node()).thenReturn(Map.of("page_id", pageId.toString()));
        when(pageRepository.findById(pageId)).thenReturn(Optional.empty());

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.CONTINUE, outcome);
        verify(pageRepository).findById(pageId);
        verify(pageRepository, never()).save(any());
    }

    @Test
    void archiveSuccess_updatesStatusAndReturnsContinue() {
        UUID pageId = UUID.randomUUID();
        UUID assignee = UUID.randomUUID();
        WikiPageEntity page = new WikiPageEntity();
        page.setId(pageId);
        page.setStatus("DRAFT");

        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.node()).thenReturn(Map.of("page_id", pageId.toString()));
        when(ctx.defaultAssignee()).thenReturn(assignee);
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(pageRepository.save(page)).thenReturn(page);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.CONTINUE, outcome);
        assertEquals("ARCHIVED", page.getStatus());
        assertEquals(assignee, page.getUpdatedBy());
        assertNotNull(page.getUpdatedAt());
        verify(pageRepository).save(page);
    }

    @Test
    void invalidUuid_returnsFailed() {
        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.node()).thenReturn(Map.of("page_id", "not-a-uuid"));

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.FAILED, outcome);
        verify(pageRepository, never()).findById(any());
        verify(pageRepository, never()).save(any());
    }

    @Test
    void type_returnsWikiArchive() {
        assertEquals("WIKI_ARCHIVE", handler.type());
    }
}
