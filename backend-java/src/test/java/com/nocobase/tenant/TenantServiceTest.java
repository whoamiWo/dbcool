package com.nocobase.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * TenantService 单元测试(Week 41 D6 Step G1).
 *
 * <p>覆盖 CRUD + 校验 + 默认租户种子。
 */
class TenantServiceTest {

    private TenantRepository repository;
    private TenantService service;

    @BeforeEach
    void setUp() {
        repository = mock(TenantRepository.class);
        service = new TenantService(repository);
    }

    @Test
    void create_validId_persists() {
        when(repository.existsById("tenant_acme")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TenantEntity t = service.create("tenant_acme", "ACME 公司", "acme");

        assertThat(t.getId()).isEqualTo("tenant_acme");
        assertThat(t.getSchemaName()).isEqualTo("tenant_acme");
        assertThat(t.getStatus()).isEqualTo(TenantEntity.Status.ACTIVE);
    }

    @Test
    void create_invalidId_throws400() {
        assertThatThrownBy(() -> service.create("AB", "x", "y"))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> service.create("123-nope", "x", "y"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void create_duplicateId_throws409() {
        when(repository.existsById("tenant_dup")).thenReturn(true);
        assertThatThrownBy(() -> service.create("tenant_dup", "x", "y"))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.CONFLICT);
    }

    @Test
    void disable_existingTenant_setsStatus() {
        TenantEntity existing = new TenantEntity("tenant_acme", "ACME", "acme");
        when(repository.findById("tenant_acme")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TenantEntity t = service.disable("tenant_acme");

        assertThat(t.getStatus()).isEqualTo(TenantEntity.Status.DISABLED);
    }

    @Test
    void disable_defaultTenant_rejected400() {
        // 默认租户不可禁用 — 否则破坏 Week 1-40 单租户数据兼容
        assertThatThrownBy(() -> service.disable(TenantContext.DEFAULT_TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void disable_unknownTenant_throws404() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.disable("ghost"))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND);
    }

    @Test
    void seedDefaultIfEmpty_emptyRepo_createsDefault() {
        when(repository.count()).thenReturn(0L);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.seedDefaultIfEmpty();

        org.mockito.Mockito.verify(repository).save(org.mockito.ArgumentMatchers.argThat(
                t -> TenantContext.DEFAULT_TENANT.equals(((TenantEntity) t).getId())
        ));
    }

    @Test
    void seedDefaultIfEmpty_existingRepo_noOp() {
        when(repository.count()).thenReturn(5L);

        service.seedDefaultIfEmpty();

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void listActive_filtersByStatus() {
        when(repository.findByStatus(TenantEntity.Status.ACTIVE)).thenReturn(List.of());

        List<TenantEntity> result = service.listActive();

        assertThat(result).isEmpty();
        org.mockito.Mockito.verify(repository).findByStatus(TenantEntity.Status.ACTIVE);
    }
}
