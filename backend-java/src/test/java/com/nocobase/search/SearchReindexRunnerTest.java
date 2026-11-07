package com.nocobase.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/** T5.1：存量重建入口（--reindex-search）；R3：必须 keyset 全量分页。 */
class SearchReindexRunnerTest {

    private TenantRepository tenantRepository;
    private CollectionService collectionService;
    private SearchIndexSyncListener listener;
    private SearchReindexRunner runner;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        collectionService = mock(CollectionService.class);
        listener = mock(SearchIndexSyncListener.class);
        runner = new SearchReindexRunner(tenantRepository, collectionService, listener);
    }

    private ApplicationArguments args(boolean reindex) {
        ApplicationArguments a = mock(ApplicationArguments.class);
        when(a.containsOption("reindex-search")).thenReturn(reindex);
        return a;
    }

    @Test
    @DisplayName("未带 --reindex-search → 不执行")
    void withoutFlag_noop() {
        runner.run(args(false));
        verify(tenantRepository, never()).findByStatus(any());
    }

    @Test
    @DisplayName("带 flag → 逐租户逐集合调用同一推断逻辑重建")
    void withFlag_reindexesRecords() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("t1");
        when(tenantRepository.findByStatus(any())).thenReturn(List.of(tenant));
        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setName("customer");
        when(collectionService.list("t1")).thenReturn(List.of(meta));
        when(collectionService.listRecordsAfter("customer", "t1", null, 500))
                .thenReturn(List.of(Map.of("id", "r1", "name", "甲公司")));

        runner.run(args(true));

        verify(listener).reindexRecord("customer", "r1", "t1",
                Map.of("id", "r1", "name", "甲公司"));
    }

    @Test
    @DisplayName("超过一页 → keyset 循环直到空页，不遗漏")
    void moreThanOnePage_reindexesAll() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("t1");
        when(tenantRepository.findByStatus(any())).thenReturn(List.of(tenant));
        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setName("big");
        when(collectionService.list("t1")).thenReturn(List.of(meta));

        // 1200 条：500 + 500 + 200
        List<Map<String, Object>> page1 = rows(0, 500);
        List<Map<String, Object>> page2 = rows(500, 1000);
        List<Map<String, Object>> page3 = rows(1000, 1200);
        when(collectionService.listRecordsAfter("big", "t1", null, 500)).thenReturn(page1);
        when(collectionService.listRecordsAfter("big", "t1", "row-499", 500)).thenReturn(page2);
        when(collectionService.listRecordsAfter("big", "t1", "row-999", 500)).thenReturn(page3);
        when(collectionService.listRecordsAfter("big", "t1", "row-1199", 500)).thenReturn(List.of());

        runner.run(args(true));

        verify(listener, times(1200)).reindexRecord(eq("big"), anyString(), eq("t1"), any());
        verify(collectionService).listRecordsAfter("big", "t1", "row-499", 500);
        verify(collectionService).listRecordsAfter("big", "t1", "row-999", 500);
    }

    @Test
    @DisplayName("满页但全部 id 为空 → 游标无法前进，终止而不是死循环")
    void fullPageWithoutValidIds_terminates() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("t1");
        when(tenantRepository.findByStatus(any())).thenReturn(List.of(tenant));
        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setName("dirty");
        when(collectionService.list("t1")).thenReturn(List.of(meta));

        List<Map<String, Object>> nullIds = new ArrayList<>();
        for (int i = 0; i < 500; i++) nullIds.add(Map.of("name", "row"));
        when(collectionService.listRecordsAfter("dirty", "t1", null, 500))
                .thenReturn(nullIds);

        runner.run(args(true));

        // 只读一页 —— 旧实现会在 afterId=null 上无限循环
        verify(collectionService, times(1)).listRecordsAfter("dirty", "t1", null, 500);
        verify(listener, never()).reindexRecord(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("游标未前进（重复 id）→ 终止")
    void cursorNotAdvancing_terminates() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("t1");
        when(tenantRepository.findByStatus(any())).thenReturn(List.of(tenant));
        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setName("stuck");
        when(collectionService.list("t1")).thenReturn(List.of(meta));

        List<Map<String, Object>> first = new ArrayList<>();
        for (int i = 0; i < 500; i++) first.add(Map.of("id", "same-id"));
        when(collectionService.listRecordsAfter("stuck", "t1", null, 500)).thenReturn(first);
        when(collectionService.listRecordsAfter("stuck", "t1", "same-id", 500)).thenReturn(first);

        runner.run(args(true));

        verify(collectionService, times(1)).listRecordsAfter("stuck", "t1", "same-id", 500);
    }

    private static List<Map<String, Object>> rows(int from, int to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            out.add(Map.of("id", "row-" + i));
        }
        return out;
    }

    @Test
    @DisplayName("单集合失败不影响其它集合")
    void collectionFailure_isolated() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("t1");
        when(tenantRepository.findByStatus(any())).thenReturn(List.of(tenant));
        CollectionMetaEntity bad = new CollectionMetaEntity();
        bad.setName("bad");
        CollectionMetaEntity good = new CollectionMetaEntity();
        good.setName("good");
        when(collectionService.list("t1")).thenReturn(List.of(bad, good));
        when(collectionService.listRecordsAfter("bad", "t1", null, 500))
                .thenThrow(new RuntimeException("broken"));
        when(collectionService.listRecordsAfter("good", "t1", null, 500))
                .thenReturn(List.of(Map.of("id", "r2")));

        runner.run(args(true));

        verify(listener).reindexRecord("good", "r2", "t1", Map.of("id", "r2"));
    }
}
