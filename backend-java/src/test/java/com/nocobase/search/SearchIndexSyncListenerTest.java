package com.nocobase.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.event.RecordChangeEvent;
import com.nocobase.im.MessageService;
import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.meta.FieldDef;
import com.nocobase.project.ProjectService;
import com.nocobase.wiki.WikiPageService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A-6 修复验证：业务记录索引写入的字段推断与元数据白名单。
 *
 * <p>修复前只读 {@code data.title}/{@code data.content}（业务集合通常没有），
 * 于是索引写入空内容 → 业务记录永远搜不到；metadata 还塞整条记录（含全部字段）。
 */
class SearchIndexSyncListenerTest {

    private UnifiedSearchService searchService;
    private CollectionService collectionService;
    private SearchIndexSyncListener listener;

    @BeforeEach
    void setUp() {
        searchService = mock(UnifiedSearchService.class);
        collectionService = mock(CollectionService.class);
        listener = new SearchIndexSyncListener(
                searchService,
                mock(WikiPageService.class),
                mock(MessageService.class),
                mock(ProjectService.class),
                collectionService);
    }

    private void stubFields(FieldDef... fields) {
        CollectionMetaEntity meta = new CollectionMetaEntity();
        when(collectionService.get(eq("customer"), anyString())).thenReturn(meta);
        when(collectionService.parseFields(meta)).thenReturn(List.of(fields));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureMeta() {
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(searchService).indexEntity(eq("record"), anyString(), anyString(),
                anyString(), anyString(), metaCaptor.capture());
        return metaCaptor.getValue();
    }

    @Test
    @DisplayName("业务记录（无 title/content，字段是 customer_name/remark）→ 索引标题与正文非空")
    void businessRecord_indexesInferredTitleAndContent() {
        stubFields(
                new FieldDef("customer_name", "text", true, "客户名称", Map.of()),
                new FieldDef("remark", "text", false, "备注", Map.of()),
                new FieldDef("amount", "number", false, "金额", Map.of()));

        listener.onRecordChange(new RecordChangeEvent(
                RecordChangeEvent.ChangeType.CREATE, "customer", UUID.randomUUID().toString(),
                Map.of("customer_name", "华东制造", "remark", "重点客户", "amount", 12000),
                "tenant_a", UUID.randomUUID()));

        verify(searchService).indexEntity(eq("record"), anyString(), eq("tenant_a"),
                eq("华东制造"), org.mockito.ArgumentMatchers.contains("华东制造"),
                any(Map.class));
    }

    @Test
    @DisplayName("metadata 只含白名单（collection/recordId/displayValue），不含整条记录")
    void metadata_isWhitelisted_noRawRecordLeak() {
        stubFields(new FieldDef("customer_name", "text", true, "客户名称", Map.of()));

        listener.onRecordChange(new RecordChangeEvent(
                RecordChangeEvent.ChangeType.UPDATE, "customer", "rec-1",
                Map.of("customer_name", "甲公司", "secret_salary", 999),
                "tenant_a", UUID.randomUUID()));

        Map<String, Object> meta = captureMeta();
        assertThat(meta).containsEntry("collection", "customer")
                .containsEntry("recordId", "rec-1")
                .containsEntry("displayValue", "甲公司");
        assertThat(meta).doesNotContainKey("secret_salary");
        assertThat(meta).doesNotContainKey("data");
    }

    @Test
    @DisplayName("无文本字段 → 标题退化为「集合 #id 前 8 位」，不写空标题")
    void noTextField_fallsBackToCollectionAndId() {
        stubFields(new FieldDef("amount", "number", false, "金额", Map.of()));

        listener.onRecordChange(new RecordChangeEvent(
                RecordChangeEvent.ChangeType.CREATE, "customer", "abcdef1234567890",
                Map.of("amount", 1), "tenant_a", UUID.randomUUID()));

        verify(searchService).indexEntity(eq("record"), eq("abcdef1234567890"), eq("tenant_a"),
                eq("customer #abcdef12"), anyString(), any(Map.class));
    }

    @Test
    @DisplayName("DELETE → 移除索引，不写空内容")
    void delete_removesIndex() {
        listener.onRecordChange(new RecordChangeEvent(
                RecordChangeEvent.ChangeType.DELETE, "customer", "rec-9", null,
                "tenant_a", UUID.randomUUID()));

        verify(searchService).removeEntity("record", "rec-9", "tenant_a");
        verify(searchService, never()).indexEntity(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("元数据不可得 → 仍写入（退化用标量值），不能整条丢失")
    void metadataUnavailable_stillIndexes() {
        when(collectionService.get(anyString(), anyString())).thenThrow(new RuntimeException("meta down"));

        listener.onRecordChange(new RecordChangeEvent(
                RecordChangeEvent.ChangeType.CREATE, "customer", "rec-2",
                Map.of("name", "乙公司"), "tenant_a", UUID.randomUUID()));

        verify(searchService).indexEntity(eq("record"), eq("rec-2"), eq("tenant_a"),
                eq("乙公司"), org.mockito.ArgumentMatchers.contains("乙公司"), any(Map.class));
    }
}
