package com.nocobase.compliance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nocobase.ai.AiConversationEntity;
import com.nocobase.ai.AiConversationEntityRepository;
import com.nocobase.audit.AuditLogRepository;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.im.ImMessageRepository;
import com.nocobase.tenant.UserTenantRepository;
import com.nocobase.userdata.UserDataErasureRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE111：AI 会话匿名化（GDPR right to be forgotten）单测。
 *
 * <p>此前 {@code eraseAiConversations} 是假实现（只 log.warn + 计数 0 却
 * {@code setSuccess(true)}），而既有 {@code UserDataErasureServiceTest} 从未给
 * AI 会话仓储打桩、也从未断言 {@code conversationsErased} —— 因此假实现也能全绿。
 *
 * <p>本类覆盖四条<b>均可失败</b>的路径：
 * <ol>
 *   <li>计数正确：真实受影响行数回填到 conversationsErased</li>
 *   <li>仓储异常传播：不得被吞掉后报成功</li>
 *   <li>残留校验：UPDATE 未生效（仍有原始内容）必须失败</li>
 *   <li>租户隔离：匿名化条件必须带 tenantId</li>
 * </ol>
 *
 * <p>可失败性：把 {@code eraseAiConversations} 改回"log.warn + 计数 0"后，
 * 用例 1 会因 conversationsErased==0 ≠ 3 而失败（实测见 PHASE111 报告）。
 */
public class UserDataErasureAiConversationTest {

    private AuditLogRepository auditLogRepository;
    private AuditService auditService;
    private AiConversationEntityRepository aiConversationRepo;
    private UserTenantRepository userTenantRepo;
    private ImMessageRepository imMessageRepo;
    private UserRepository userRepo;
    private UserDataErasureLogRepository erasureLogRepo;
    private UserDataErasureService service;

    private static final String TENANT = "tenant_default";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        auditService = mock(AuditService.class);
        aiConversationRepo = mock(AiConversationEntityRepository.class);
        userTenantRepo = mock(UserTenantRepository.class);
        imMessageRepo = mock(ImMessageRepository.class);
        userRepo = mock(UserRepository.class);
        erasureLogRepo = mock(UserDataErasureLogRepository.class);
        service = new UserDataErasureService(auditLogRepository, auditService,
                aiConversationRepo, userTenantRepo, imMessageRepo, userRepo, erasureLogRepo);

        // 归属校验放行（requireUserInTenant）
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setTenantId(TENANT);
        when(userRepo.findByIdAndTenantId(USER_ID, TENANT)).thenReturn(Optional.of(user));

        // 其余两步（IM / 审计）不干扰本类断言
        when(userTenantRepo.findByUserId(String.valueOf(USER_ID))).thenReturn(List.of());
        when(auditLogRepository.findByFilter(anyString(), any(), any(), anyString(), any()))
                .thenReturn(List.of());
        when(imMessageRepo.anonymizeContentBySender(any(UUID.class), anyString())).thenReturn(0);
        when(auditLogRepository.anonymizeUsername(anyString(), anyString(), anyString()))
                .thenReturn(0);
    }

    private static AiConversationEntity conv(String messagesJson) {
        AiConversationEntity c = new AiConversationEntity();
        c.setId(UUID.randomUUID());
        c.setTenantId(TENANT);
        c.setUserId(USER_ID);
        c.setMessagesJson(messagesJson);
        return c;
    }

    /** 1) 计数正确：把仓储返回的真实行数回填到 conversationsErased。 */
    @Test
    void eraseAiConversations_countsRealAffectedRows() {
        when(aiConversationRepo.anonymizeMessagesByUserId(any(UUID.class), anyString(), anyString()))
                .thenReturn(3);
        when(aiConversationRepo.findByUserIdAndTenantId(any(UUID.class), anyString()))
                .thenReturn(List.of());

        UserDataErasureRequest result =
                service.erasesUserData(String.valueOf(USER_ID), String.valueOf(ADMIN_ID), TENANT);

        assertTrue(result.isSuccess(), "三步均完成后应标记成功");
        assertEquals(3, result.getConversationsErased(),
                "conversationsErased 必须是真实受影响行数（假实现恒为 0）");
    }

    /** 2) 仓储异常必须向上传播，不能被吞掉后仍报成功（红线 2）。 */
    @Test
    void eraseAiConversations_failurePropagates_noSilentSuccess() {
        when(aiConversationRepo.anonymizeMessagesByUserId(any(UUID.class), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.erasesUserData(String.valueOf(USER_ID), String.valueOf(ADMIN_ID), TENANT),
                "匿名化失败必须抛异常，不得静默继续并 setSuccess(true)");
        assertTrue(ex.getMessage().contains("AI 会话匿名化失败"),
                "异常信息应指明失败环节，实际=" + ex.getMessage());

        // 失败路径不得执行到审计留痕之后的 setSuccess：此处以"未在成功路径打审计"间接确认
        verify(auditService, never()).log(anyString(), anyString(), anyString(),
                eq("user.data.erasure"), anyString(), anyString(), any());
    }

    /** 3) 残留校验：UPDATE 静默未生效（仍有原始内容）必须失败。 */
    @Test
    void eraseAiConversations_residualCheck_failsWhenContentRemains() {
        when(aiConversationRepo.anonymizeMessagesByUserId(any(UUID.class), anyString(), anyString()))
                .thenReturn(1);
        // 复查仍能查到含原始内容的会话 → 说明匿名化实际上没生效
        when(aiConversationRepo.findByUserIdAndTenantId(any(UUID.class), anyString()))
                .thenReturn(List.of(conv("[{\"role\":\"user\",\"content\":\"secret\"}]")));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.erasesUserData(String.valueOf(USER_ID), String.valueOf(ADMIN_ID), TENANT),
                "残留未清理时必须失败，不能只 warn 后继续");
        assertTrue(ex.getMessage().contains("未生效"),
                "异常信息应说明仍有残留，实际=" + ex.getMessage());
    }

    /** 4) 租户隔离：匿名化条件必须带 tenantId（防跨租户误删）。 */
    @Test
    void eraseAiConversations_passesTenantIdAndJsonPlaceholder() {
        when(aiConversationRepo.anonymizeMessagesByUserId(any(UUID.class), anyString(), anyString()))
                .thenReturn(2);
        when(aiConversationRepo.findByUserIdAndTenantId(any(UUID.class), anyString()))
                .thenReturn(List.of());

        service.erasesUserData(String.valueOf(USER_ID), String.valueOf(ADMIN_ID), TENANT);

        // 关键：tenantId 必须传入；占位必须是合法 JSON "[]"（非 "[已删除]" 这类文本）
        verify(aiConversationRepo).anonymizeMessagesByUserId(eq(USER_ID), eq(TENANT), eq("[]"));
        verify(aiConversationRepo).findByUserIdAndTenantId(eq(USER_ID), eq(TENANT));
    }

    /** 5) 用户本就没有会话（0 条且无残留）属正常成功，不得因 0 判定失败。 */
    @Test
    void eraseAiConversations_zeroRows_stillSucceeds() {
        when(aiConversationRepo.anonymizeMessagesByUserId(any(UUID.class), anyString(), anyString()))
                .thenReturn(0);
        when(aiConversationRepo.findByUserIdAndTenantId(any(UUID.class), anyString()))
                .thenReturn(List.of());

        UserDataErasureRequest result =
                service.erasesUserData(String.valueOf(USER_ID), String.valueOf(ADMIN_ID), TENANT);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getConversationsErased());
    }

    // ── PHASE112：删除请求台账（user_data_erasure_log / V52 表）──

    /** 6) 删除成功路径必须把台账写入 user_data_erasure_log（字段精确、与 result 一致）。 */
    @Test
    void erasure_success_writesLedgerWithExactCounts() {
        when(aiConversationRepo.anonymizeMessagesByUserId(any(UUID.class), anyString(), anyString()))
                .thenReturn(4);
        when(aiConversationRepo.findByUserIdAndTenantId(any(UUID.class), anyString()))
                .thenReturn(List.of());

        UserDataErasureRequest result =
                service.erasesUserData(String.valueOf(USER_ID), String.valueOf(ADMIN_ID), TENANT);

        verify(erasureLogRepo).save(argThat(ledger -> {
            assertEquals(String.valueOf(USER_ID), ledger.getUserId());
            assertEquals(TENANT, ledger.getTenantId());
            assertEquals(String.valueOf(ADMIN_ID), ledger.getRequesterId());
            assertNotNull(ledger.getAnonymousId(), "匿名标识必须落台账");
            assertEquals(4, ledger.getConversationsErased(),
                    "台账计数必须与 result.conversationsErased 一致（精确）");
            assertEquals(result.getMessagesErased(), ledger.getMessagesErased());
            assertEquals(result.getAuditLogsAnonymized(), ledger.getAuditLogsAnonymized());
            assertNotNull(ledger.getId(), "台账 id 由 @PrePersist 生成");
            return true;
        }));
    }

    /** 7) 删除失败（残留未清理）必须回滚 —— 台账不得写入（同事务回滚）。 */
    @Test
    void erasure_failure_doesNotWriteLedger() {
        when(aiConversationRepo.anonymizeMessagesByUserId(any(UUID.class), anyString(), anyString()))
                .thenReturn(1);
        // 复查仍残留原始内容 → 服务抛 IllegalStateException
        when(aiConversationRepo.findByUserIdAndTenantId(any(UUID.class), anyString()))
                .thenReturn(List.of(conv("[{\"role\":\"user\",\"content\":\"secret\"}]")));

        assertThrows(IllegalStateException.class,
                () -> service.erasesUserData(String.valueOf(USER_ID), String.valueOf(ADMIN_ID), TENANT));

        verify(erasureLogRepo, never()).save(any());
    }
}