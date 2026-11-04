package com.nocobase.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/** T5.1：存量重建入口（--reindex-search）。 */
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
        when(collectionService.listRecords("customer", "t1", 500))
                .thenReturn(List.of(Map.of("id", "r1", "name", "甲公司")));

        runner.run(args(true));

        verify(listener).reindexRecord("customer", "r1", "t1",
                Map.of("id", "r1", "name", "甲公司"));
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
        when(collectionService.listRecords("bad", "t1", 500))
                .thenThrow(new RuntimeException("broken"));
        when(collectionService.listRecords("good", "t1", 500))
                .thenReturn(List.of(Map.of("id", "r2")));

        runner.run(args(true));

        verify(listener).reindexRecord("good", "r2", "t1", Map.of("id", "r2"));
    }
}
