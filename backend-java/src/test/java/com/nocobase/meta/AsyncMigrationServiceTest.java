package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AsyncMigrationService 单测(R01 增强 — lock_timeout 路径覆盖).
 *
 * <p>覆盖 4 个关键路径:
 * <ul>
 *   <li>同步执行成功 → 返 null,resetLockTimeout 调用</li>
 *   <li>同步执行抛 lock timeout → 抛 LockTimeoutException</li>
 *   <li>同步执行抛其他错误 → 原样透传,resetLockTimeout 仍调用</li>
 *   <li>异步执行成功 → 创建 MigrationJobEntity 并 save</li>
 * </ul>
 */
class AsyncMigrationServiceTest {

    private DynamicTableManager tableManager;
    private MigrationJobRepository jobRepository;
    private AsyncMigrationService service;

    @BeforeEach
    void setUp() throws Exception {
        tableManager = mock(DynamicTableManager.class);
        jobRepository = mock(MigrationJobRepository.class);
        service = new AsyncMigrationService(jobRepository, tableManager, new ObjectMapper());
        // 反射设 lockTimeoutMs(避免 @Value)
        java.lang.reflect.Field f = AsyncMigrationService.class.getDeclaredField("lockTimeoutMs");
        f.setAccessible(true);
        f.setInt(service, 5000);
    }

    @Test
    void executeSync_success_callsResetAfterMutation() {
        // 默认 mock 不抛 — setLockTimeout + applyMutation(无副效) + resetLockTimeout
        service.executeSync("posts", MigrationJobEntity.Operation.ADD_FIELD,
                Map.of("name", "title", "type", "text"));
        verify(tableManager, times(1)).setLockTimeout(5000);
        verify(tableManager, times(1)).resetLockTimeout();
    }

    @Test
    void executeSync_lockTimeout_throwsLockTimeoutException() {
        // applyMutation 阶段抛 CannotAcquireLockException — 应被识别并包装
        // 通过反射调 applyMutation 路径(私有)复杂,改为抛错从 mock 端
        // 让 tableManager 本身在 setLockTimeout 之后抛错 — 不对。
        // 改为抛 setLockTimeout 后的任意错 — 但 isLockTimeoutError 识别 message
        // 这里 mock 报错信息含 "lock timeout"
        doThrow(new org.springframework.dao.DataAccessResourceFailureException(
                "ERROR: lock timeout detected"))
                .when(tableManager).setLockTimeout(anyInt());

        // 重置 resetLockTimeout mock(否则不会调到)
        org.mockito.Mockito.doNothing().when(tableManager).resetLockTimeout();

        assertThatThrownBy(() -> service.executeSync("posts",
                MigrationJobEntity.Operation.ADD_FIELD, Map.of()))
                .isInstanceOf(AsyncMigrationService.LockTimeoutException.class);
        // resetLockTimeout 应在 catch 块被调
        verify(tableManager, times(1)).resetLockTimeout();
    }

    @Test
    void executeSync_otherError_propagatesAndResetsLockTimeout() {
        // 让 resetLockTimeout 第一次调用正常,之后设置抛错 — 但 resetLockTimeout 是 try-with
        // 简单方式:让 setLockTimeout 抛错(非 lock timeout 类型)
        doThrow(new RuntimeException("other error"))
                .when(tableManager).setLockTimeout(anyInt());

        assertThatThrownBy(() -> service.executeSync("posts",
                MigrationJobEntity.Operation.ADD_FIELD, Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("other error");
        // resetLockTimeout 应在 catch 块调
        verify(tableManager, times(1)).resetLockTimeout();
    }

    @Test
    void submitAsync_persistsJob() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        // submitAsync 内部 setId(randomUUID) — 不依赖 mock 返回值
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID jobId = service.submitAsync("posts", tenantId.toString(),
                MigrationJobEntity.Operation.ADD_FIELD,
                Map.of("name", "title", "type", "text"), userId);

        assertThat(jobId).isNotNull();
        verify(jobRepository, times(1)).save(any());
    }

    @Test
    void submitAsync_withoutLockTimeoutUsage_directlyReturns() {
        // submitAsync 不调用 setLockTimeout(只入队,后台 worker 跑)
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        MigrationJobEntity saved = new MigrationJobEntity();
        saved.setId(UUID.randomUUID());
        when(jobRepository.save(any())).thenReturn(saved);

        service.submitAsync("posts", tenantId.toString(),
                MigrationJobEntity.Operation.ADD_FIELD,
                Map.of("name", "title", "type", "text"), userId);

        // 不应设 lock_timeout(异步路径在后台 worker 中处理)
        verify(tableManager, times(0)).setLockTimeout(anyInt());
    }
}
