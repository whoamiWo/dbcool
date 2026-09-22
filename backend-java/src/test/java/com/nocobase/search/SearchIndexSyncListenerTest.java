package com.nocobase.search;

import com.nocobase.event.RecordChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

/**
 * 搜索索引同步监听器测试 — 事件驱动写入索引（防"编译绿、运行空"）。
 */
@ExtendWith(MockitoExtension.class)
class SearchIndexSyncListenerTest {

    @Mock
    private UnifiedSearchService searchService;

    @Mock
    private com.nocobase.wiki.WikiPageService wikiPageService;

    @Mock
    private com.nocobase.im.MessageService messageService;

    @Mock
    private com.nocobase.project.ProjectService projectService;

    @InjectMocks
    private SearchIndexSyncListener listener;

    private final java.util.UUID wikiId = java.util.UUID.randomUUID();
    private final java.util.UUID msgId = java.util.UUID.randomUUID();
    private final java.util.UUID taskId = java.util.UUID.randomUUID();

    @Test
    void wikiPage_create_indexes() {
        com.nocobase.wiki.WikiPageEntity page = new com.nocobase.wiki.WikiPageEntity();
        page.setTitle("t");
        page.setContent("c");
        when(wikiPageService.get(wikiId)).thenReturn(page);
        RecordChangeEvent evt = new RecordChangeEvent(
                RecordChangeEvent.ChangeType.CREATE, "wiki_page", wikiId.toString(), null, "t-1", java.util.UUID.randomUUID());
        listener.onRecordChange(evt);
        verify(searchService).indexEntity(eq("wiki"), eq(wikiId.toString()), eq("t-1"), any(), any(), any());
    }

    @Test
    void imMessage_delete_removes() {
        RecordChangeEvent evt = new RecordChangeEvent(
                RecordChangeEvent.ChangeType.DELETE, "im_message", msgId.toString(), null, "t-1", java.util.UUID.randomUUID());
        listener.onRecordChange(evt);
        verify(searchService).removeEntity("im", msgId.toString(), "t-1");
    }

    @Test
    void projectTask_update_indexes() {
        com.nocobase.project.ProjectTaskEntity task = new com.nocobase.project.ProjectTaskEntity();
        task.setTitle("t");
        task.setDescription("d");
        when(projectService.get(taskId, "t-1")).thenReturn(task);
        RecordChangeEvent evt = new RecordChangeEvent(
                RecordChangeEvent.ChangeType.UPDATE, "project_task", taskId.toString(), null, "t-1", java.util.UUID.randomUUID());
        listener.onRecordChange(evt);
        verify(searchService).indexEntity(eq("project"), eq(taskId.toString()), eq("t-1"), any(), any(), any());
    }
}
