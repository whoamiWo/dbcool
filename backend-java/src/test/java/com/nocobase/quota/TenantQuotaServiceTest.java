package com.nocobase.quota;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.nocobase.audit.AuditService;
import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;

class TenantQuotaServiceTest {

    private TenantRepository tenantRepository;
    private AuditService auditService;
    private TenantQuotaService service;

    private static final String TENANT_ID = "tenant_test";
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        auditService = mock(AuditService.class);
        service = new TenantQuotaService(tenantRepository, auditService);

        tenant = new TenantEntity(TENANT_ID, "Test Tenant", "test");
        tenant.setApiCallLimit(5L);  // 5 req/min
        tenant.setApiCallUsage(0L);
        tenant.setStorageLimit(100L);  // 100 bytes
        tenant.setStorageUsage(0L);
        tenant.setSeatsLimit(3L);
        tenant.setSeatsUsed(0L);

        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
    }

    // === API 配额 ===

    @Test
    void apiQuota_allows_when_under_limit() {
        for (int i = 0; i < 5; i++) {
            service.checkAndConsumeApiQuota(TENANT_ID);
        }
        assertThat(tenant.getApiCallUsage()).isEqualTo(5L);
    }

    @Test
    void apiQuota_throws_429_when_over_limit() {
        for (int i = 0; i < 5; i++) {
            service.checkAndConsumeApiQuota(TENANT_ID);
        }
        assertThatThrownBy(() -> service.checkAndConsumeApiQuota(TENANT_ID))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void apiQuota_resets_usage() {
        service.checkAndConsumeApiQuota(TENANT_ID);
        service.checkAndConsumeApiQuota(TENANT_ID);
        assertThat(tenant.getApiCallUsage()).isEqualTo(2L);
        service.resetApiUsage(TENANT_ID);
        assertThat(tenant.getApiCallUsage()).isEqualTo(0L);
    }

    // === 存储配额 ===

    @Test
    void storageQuota_allows_when_under_limit() {
        service.checkStorageQuota(TENANT_ID, 50L);
        assertThat(tenant.getStorageUsage()).isEqualTo(50L);
    }

    @Test
    void storageQuota_throws_403_when_over_limit() {
        service.checkStorageQuota(TENANT_ID, 50L);
        assertThatThrownBy(() -> service.checkStorageQuota(TENANT_ID, 60L))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void storageQuota_reduces_on_delete() {
        service.checkStorageQuota(TENANT_ID, 50L);
        service.reduceStorageUsage(TENANT_ID, 30L);
        assertThat(tenant.getStorageUsage()).isEqualTo(20L);
    }

    // === 席位配额 ===

    @Test
    void seatsQuota_allows_when_under_limit() {
        service.checkSeatsQuota(TENANT_ID);
        service.checkSeatsQuota(TENANT_ID);
        service.checkSeatsQuota(TENANT_ID);
        assertThat(tenant.getSeatsUsed()).isEqualTo(3L);
    }

    @Test
    void seatsQuota_throws_403_when_full() {
        service.checkSeatsQuota(TENANT_ID);
        service.checkSeatsQuota(TENANT_ID);
        service.checkSeatsQuota(TENANT_ID);
        assertThatThrownBy(() -> service.checkSeatsQuota(TENANT_ID))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void seatsQuota_releases() {
        service.checkSeatsQuota(TENANT_ID);
        service.checkSeatsQuota(TENANT_ID);
        service.releaseSeatsQuota(TENANT_ID);
        assertThat(tenant.getSeatsUsed()).isEqualTo(1L);
    }

    // === 计量查询 ===

    @Test
    void getQuotaStatus_returns_all_fields() {
        var status = service.getQuotaStatus(TENANT_ID);
        assertThat(status.apiCallLimit()).isEqualTo(5L);
        assertThat(status.storageLimit()).isEqualTo(100L);
        assertThat(status.seatsLimit()).isEqualTo(3L);
    }

    @Test
    void tenant_not_found_throws() {
        when(tenantRepository.findById("nonexistent")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.checkAndConsumeApiQuota("nonexistent"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
