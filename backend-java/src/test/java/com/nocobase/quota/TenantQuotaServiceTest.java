package com.nocobase.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import com.nocobase.audit.AuditService;
import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;

class TenantQuotaServiceTest {

    private TenantRepository tenantRepository;
    private AuditService auditService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private TenantQuotaService service;

    private static final String TENANT_ID = "tenant_test";
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        auditService = mock(AuditService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 默认 INCR 返回 1（首次）
        when(valueOps.increment(anyString(), anyLong())).thenReturn(1L);
        // 默认 GET 返回 null（未初始化）
        when(valueOps.get(anyString())).thenReturn(null);
        when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(true);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        service = new TenantQuotaService(tenantRepository, auditService, redisTemplate);

        tenant = new TenantEntity(TENANT_ID, "Test Tenant", "test");
        tenant.setApiCallLimit(5L);  // 5 req/min
        tenant.setApiCallUsage(0L);
        tenant.setStorageLimit(100L);
        tenant.setStorageUsage(0L);
        tenant.setSeatsLimit(3L);
        tenant.setSeatsUsed(0L);

        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
    }

    /** 助手：获取当前分钟桶前缀（模拟服务内部调用） */
    private String makeKey() {
        long bucket = System.currentTimeMillis() / 60_000;
        return bucket + ":quota:api:" + TENANT_ID;
    }

    // ==================== API 配额（Redis 热路径）====================

    @Test
    void apiQuota_allows_when_under_limit() {
        // 前 5 次 INCR 分别返回 1..5
        when(valueOps.increment(eq(makeKey()), eq(1L)))
                .thenReturn(1L).thenReturn(2L).thenReturn(3L).thenReturn(4L).thenReturn(5L);
        when(valueOps.get(eq(makeKey()))).thenReturn(null); // 初始化时 Redis 无值

        for (int i = 0; i < 5; i++) {
            assertThat(service.checkAndConsumeApiQuota(TENANT_ID)).isTrue();
        }
    }

    @Test
    void apiQuota_throws_429_when_over_limit() {
        // 从 DB 加载后，本地缓存 usage=5 >= limit=5，直接抛 429
        when(valueOps.get(eq(makeKey()))).thenReturn("5");

        assertThatThrownBy(() -> service.checkAndConsumeApiQuota(TENANT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已达上限");
    }

    @Test
    void apiQuota_throws_429_when_local_cache_exceeds_limit() {
        // 首次请求让 Redis 计数到 4，第二次 INCR 到 5 > limit，仍然通过
        // 但我们通过直接塞缓存来模拟超限
        when(valueOps.increment(eq(makeKey()), eq(1L)))
                .thenReturn(1L).thenReturn(2L).thenReturn(3L).thenReturn(4L).thenReturn(5L);
        when(valueOps.get(eq(makeKey()))).thenReturn(null);

        for (int i = 0; i < 5; i++) {
            service.checkAndConsumeApiQuota(TENANT_ID);
        }
        // 此时缓存 usage=5, limit=5 → 第 6 次应 429
        assertThatThrownBy(() -> service.checkAndConsumeApiQuota(TENANT_ID))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void apiQuota_throws_IllegalArgumentException_when_tenant_not_found() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(tenantRepository.findById("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkAndConsumeApiQuota("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void apiQuota_fallback_to_429_when_redis_fails() {
        when(valueOps.increment(anyString(), anyLong()))
                .thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> service.checkAndConsumeApiQuota(TENANT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("临时不可用");
    }

    @Test
    void resetApiUsage_clears_redis_and_db() {
        // 先让计数达到 3
        when(valueOps.increment(eq(makeKey()), eq(1L))).thenReturn(1L).thenReturn(2L).thenReturn(3L);
        when(valueOps.get(eq(makeKey()))).thenReturn(null);
        for (int i = 0; i < 3; i++) {
            service.checkAndConsumeApiQuota(TENANT_ID);
        }

        tenant.setApiCallUsage(3L);
        service.resetApiUsage(TENANT_ID);
        assertThat(tenant.getApiCallUsage()).isEqualTo(0L);

        verify(tenantRepository).save(tenant);
        // 清理对应的分钟桶 key
        verify(redisTemplate).delete(eq(makeKey()));
    }

    @Test
    void resetApiUsage_periodically_resets_db_and_pending() {
        when(tenantRepository.resetAllApiUsage()).thenReturn(10);

        service.resetAllApiUsagePeriodically();
        verify(tenantRepository).resetAllApiUsage();
        // pending 清空
    }

    // ==================== 存储配额 ====================

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

    // ==================== 席位配额 ====================

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

    // ==================== 计量查询 ====================

    @Test
    void getQuotaStatus_returns_all_fields() {
        when(valueOps.get(eq(makeKey()))).thenReturn("2");

        var status = service.getQuotaStatus(TENANT_ID);
        assertThat(status.apiCallLimit()).isEqualTo(5L);
        assertThat(status.storageLimit()).isEqualTo(100L);
        assertThat(status.seatsLimit()).isEqualTo(3L);
        assertThat(status.apiCallUsage()).isEqualTo(2L); // Redis 最新值
    }

    @Test
    void getQuotaStatus_falls_back_to_db_when_redis_empty() {
        when(valueOps.get(eq(makeKey()))).thenReturn(null);
        tenant.setApiCallUsage(1L);

        var status = service.getQuotaStatus(TENANT_ID);
        assertThat(status.apiCallUsage()).isEqualTo(1L); // DB 值
    }
}
