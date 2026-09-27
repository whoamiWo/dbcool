package com.nocobase.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.acl.RowAclService;
import com.nocobase.auth.AclEnforcer;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.RoleRepository;
import com.nocobase.audit.AuditService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 批量操作 API 的控制器测试 — PHASE 56 P2-4。
 *
 * <p>背景：{@code CollectionController} 的 batch-insert / batch-update / batch-delete
 * 端点<b>早已实现</b>（L582 / L597 / L612），但此前<b>测试覆盖为 0</b>
 * （{@code src/test} 下 grep 命中 0）。本类补齐覆盖。
 *
 * <p>租户校验说明（审计确认，非臆造）：
 * {@code CollectionService.batchInsert / batchUpdate / batchDelete} 内部已有
 * {@code if (!meta.getTenantId().equals(tenantId)) throw FORBIDDEN}，
 * 因此本类重点验证「控制器把<b>当前会话租户</b>正确下传给 service」以及
 * 「service 拒绝时异常正确透传为 403」。
 */
class CollectionBatchControllerTest {

    private CollectionService service;
    private AsyncMigrationService migrationService;
    private MigrationJobRepository jobRepository;
    private AclEnforcer aclEnforcer;
    private AuditService auditService;
    private RowAclService rowAclService;
    private RoleRepository roleRepository;
    private RelationResolver relationResolver;
    private CollectionController controller;

    private AuthenticatedUser testUser;

    @BeforeEach
    void setUp() {
        service = mock(CollectionService.class);
        migrationService = mock(AsyncMigrationService.class);
        jobRepository = mock(MigrationJobRepository.class);
        aclEnforcer = mock(AclEnforcer.class);
        auditService = mock(AuditService.class);
        rowAclService = mock(RowAclService.class);
        roleRepository = mock(RoleRepository.class);
        relationResolver = mock(RelationResolver.class);
        org.springframework.context.ApplicationEventPublisher eventPublisher =
                mock(org.springframework.context.ApplicationEventPublisher.class);

        controller = new CollectionController(service, migrationService, jobRepository,
                aclEnforcer, auditService, rowAclService, roleRepository, eventPublisher,
                relationResolver);

        doNothing().when(aclEnforcer).assertCan(any(), anyString(), anyString(), any());
        when(roleRepository.findRoleNamesByUserId(any(), anyString())).thenReturn(List.of("admin"));

        testUser = new AuthenticatedUser(UUID.randomUUID(), "alice", "tenant_default");
    }

    @Test
    void batchInsert_returnsInsertedCount() {
        when(service.batchInsert(eq("users"), any(), eq("tenant_default"))).thenReturn(3);

        Map<String, Object> resp = controller.batchInsert("users",
                new CollectionController.BatchInsertRequest(List.of(
                        Map.of("name", "a"), Map.of("name", "b"), Map.of("name", "c"))),
                testUser);

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(3, data.get("inserted"));
    }

    @Test
    void batchUpdate_returnsUpdatedIds() {
        List<String> ids = List.of("id-1", "id-2");
        when(service.batchUpdate(eq("users"), any(), eq("tenant_default"))).thenReturn(ids);

        Map<String, Object> resp = controller.batchUpdate("users",
                new CollectionController.BatchUpdateRequest(List.of(
                        new CollectionService.BatchUpdateItem("id-1", Map.of("name", "x")),
                        new CollectionService.BatchUpdateItem("id-2", Map.of("name", "y")))),
                testUser);

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(ids, data.get("updated_ids"));
    }

    @Test
    void batchDelete_returnsDeletedCount() {
        when(service.batchDelete(eq("users"), any(), eq("tenant_default"))).thenReturn(2);

        Map<String, Object> resp = controller.batchDelete("users",
                new CollectionController.BatchDeleteRequest(List.of("id-1", "id-2")),
                testUser);

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(2, data.get("deleted"));
    }

    /**
     * 越权：操作他租户 collection 时 service 抛 403，控制器必须原样透传（不得吞掉）。
     */
    @Test
    void batchInsert_otherTenantCollection_propagates403() {
        when(service.batchInsert(eq("users"), any(), eq("tenant_default")))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 collection"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.batchInsert("users",
                        new CollectionController.BatchInsertRequest(List.of(Map.of("name", "a"))),
                        testUser));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    /** 租户下传：必须传当前会话租户，不能传 null 或写死值。 */
    @Test
    void batchDelete_passesCurrentSessionTenant() {
        when(service.batchDelete(any(), any(), any())).thenReturn(1);

        controller.batchDelete("users",
                new CollectionController.BatchDeleteRequest(List.of("id-1")), testUser);

        verify(service).batchDelete(eq("users"), any(), eq("tenant_default"));
    }

    /** ACL 拒绝时不得继续调用 service（反向用例）。 */
    @Test
    void batchUpdate_aclDenied_doesNotCallService() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限"))
                .when(aclEnforcer).assertCan(any(), anyString(), anyString(), any());

        assertThrows(ResponseStatusException.class,
                () -> controller.batchUpdate("users",
                        new CollectionController.BatchUpdateRequest(List.of()), testUser));

        verify(service, never()).batchUpdate(any(), any(), anyString());
    }

    /** 空列表：合法输入，返回 0 且不抛异常。 */
    @Test
    void batchInsert_emptyList_returnsZero() {
        when(service.batchInsert(eq("users"), any(), eq("tenant_default"))).thenReturn(0);

        Map<String, Object> resp = controller.batchInsert("users",
                new CollectionController.BatchInsertRequest(List.of()), testUser);

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(0, data.get("inserted"));
    }
}