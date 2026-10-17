package com.nocobase.compliance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nocobase.ai.AiConversationEntity;
import com.nocobase.ai.AiConversationEntityRepository;
import com.nocobase.tenant.UserTenantRepository;
import com.nocobase.audit.AuditLogEntity;
import com.nocobase.audit.AuditLogRepository;
import com.nocobase.audit.AuditService;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.im.ImMessageRepository;
import com.nocobase.userdata.UserDataErasureRequest;
import com.nocobase.userdata.UserDataExport;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE105: UserDataErasureService 覆盖（归属校验、导出、删除匿名化）。
 */
public class UserDataErasureServiceTest {

    private AuditLogRepository auditLogRepository;
    private AuditService auditService;
    private AiConversationEntityRepository aiConversationRepo;
    private com.nocobase.tenant.UserTenantRepository userTenantRepo;
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
        userTenantRepo = mock(com.nocobase.tenant.UserTenantRepository.class);
        imMessageRepo = mock(ImMessageRepository.class);
        userRepo = mock(UserRepository.class);
        erasureLogRepo = mock(UserDataErasureLogRepository.class);
        service = new UserDataErasureService(auditLogRepository, auditService,
                aiConversationRepo, userTenantRepo, imMessageRepo, userRepo, erasureLogRepo);

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setTenantId(TENANT);
        when(userRepo.findByIdAndTenantId(USER_ID, TENANT)).thenReturn(Optional.of(user));
        when(userTenantRepo.findByUserId(String.valueOf(USER_ID))).thenReturn(List.of());
        when(auditLogRepository.findByFilter(anyString(), any(), any(), anyString(), any())).thenReturn(List.of());
        when(imMessageRepo.anonymizeContentBySender(any(UUID.class), anyString())).thenReturn(0);
        when(auditLogRepository.anonymizeUsername(anyString(), anyString(), anyString())).thenReturn(0);
    }

    // ── requireUserInTenant ──────────────────────────────────────

    @Test
    void export_nullTenantId_forbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.exportUserData(String.valueOf(USER_ID), null, "admin"));
    }

    @Test
    void export_userNotFound_forbidden() {
        when(userRepo.findByIdAndTenantId(USER_ID, TENANT)).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.exportUserData(String.valueOf(USER_ID), TENANT, "admin"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void erasure_nullTenantId_forbidden() {
        assertThrows(ResponseStatusException.class,
                () -> service.erasesUserData(String.valueOf(USER_ID), TENANT, null));
    }

    @Test
    void erasure_userNotFound_forbidden() {
        when(userRepo.findByIdAndTenantId(USER_ID, TENANT)).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.erasesUserData(String.valueOf(USER_ID), String.valueOf(ADMIN_ID), TENANT));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void erasure_invalidUserId_forbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.erasesUserData("not-a-uuid", String.valueOf(ADMIN_ID), TENANT));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ── exportUserData ───────────────────────────────────────────

    @Test
    void exportUserData_success_returnsStructuredData() {
        UserDataExport export = service.exportUserData(String.valueOf(USER_ID), TENANT, "admin");
        assertNotNull(export.getUserId());
        assertNotNull(export.getExportedAt());
        assertEquals("admin", export.getExportedBy());
        assertNotNull(export.getUserData());
    }

    // ── erasesUserData ───────────────────────────────────────────

    @Test
    void erasesUserData_success_setsResult() {
        UserDataErasureRequest result = service.erasesUserData(
                String.valueOf(USER_ID), String.valueOf(ADMIN_ID), TENANT);
        assertTrue(result.isSuccess());
        assertEquals(TENANT, result.getTenantId());
        assertEquals(String.valueOf(ADMIN_ID), result.getRequester());
        assertNotNull(result.getRequestedAt());
    }

    @Test
    void erasesUserData_callsAnonymizeAuditLogs() {
        when(auditLogRepository.anonymizeUsername(anyString(), anyString(), anyString())).thenReturn(5);
        UserDataErasureRequest result = service.erasesUserData(
                String.valueOf(USER_ID), String.valueOf(ADMIN_ID), TENANT);
        assertEquals(5, result.getAuditLogsAnonymized());
    }

    @Test
    void erasesUserData_invalidUuid_forbidden() {
        // 非 UUID → requireUserInTenant 抛 ResponseStatusException (403)
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.erasesUserData("invalid-uuid", String.valueOf(ADMIN_ID), TENANT));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
