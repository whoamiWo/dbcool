package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** R1b：单记录接口必须与列表同源做数据范围判定（越权 → 404）。 */
class CollectionControllerScopeTest {

    private CollectionService service;
    private AclEnforcer aclEnforcer;
    private RowAclService rowAclService;
    private CollectionController controller;
    private final AuthenticatedUser user = new AuthenticatedUser(
            UUID.randomUUID(), "alice", "tenant_default");

    @BeforeEach
    void setUp() {
        service = mock(CollectionService.class);
        aclEnforcer = mock(AclEnforcer.class);
        rowAclService = mock(RowAclService.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        when(roleRepository.findRoleNamesByUserId(any(), anyString())).thenReturn(List.of("USER"));
        doNothing().when(aclEnforcer).assertCan(any(), anyString(), anyString(), any());
        doNothing().when(aclEnforcer).assertCanWriteFields(any(), anyString(), anyString(), any(), any());
        when(aclEnforcer.filterRecord(any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(3));
        when(rowAclService.evaluateRead(any(), anyString(), any(), any())).thenReturn(true);
        when(rowAclService.evaluateUpdate(any(), anyString(), any(), any())).thenReturn(true);
        when(rowAclService.evaluateDelete(any(), anyString(), any(), any())).thenReturn(true);
        controller = new CollectionController(service, mock(AsyncMigrationService.class),
                mock(MigrationJobRepository.class), aclEnforcer, mock(AuditService.class),
                rowAclService, roleRepository, mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(RelationResolver.class), mock(JsonSchemaImporter.class),
                mock(com.nocobase.export.ExportFileService.class));
    }

    private void denyScope() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或无权访问"))
                .when(aclEnforcer).assertRecordInScope(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("GET 单条越界 → 404")
    void getRecord_outOfScope_404() {
        when(service.getRecord("posts", "r1", "tenant_default")).thenReturn(Map.of("id", "r1"));
        denyScope();

        assertThatThrownBy(() -> controller.getRecord("posts", "r1", user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    @DisplayName("PUT 单条越界 → 404")
    void updateRecord_outOfScope_404() {
        when(service.getRecord("posts", "r1", "tenant_default")).thenReturn(Map.of("id", "r1"));
        denyScope();

        assertThatThrownBy(() -> controller.updateRecord("posts", "r1",
                Map.of("title", "x"), user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    @DisplayName("DELETE 单条越界 → 404")
    void deleteRecord_outOfScope_404() {
        when(service.getRecord("posts", "r1", "tenant_default")).thenReturn(Map.of("id", "r1"));
        denyScope();

        assertThatThrownBy(() -> controller.deleteRecord("posts", "r1", user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    @DisplayName("批量删除：任一 id 越界 → 整批 404")
    void batchDelete_anyOutOfScope_404() {
        when(service.getRecord(eq("posts"), eq("u1"), eq("tenant_default")))
                .thenReturn(Map.of("id", "u1"));
        when(service.getRecord(eq("posts"), eq("u2"), eq("tenant_default")))
                .thenReturn(Map.of("id", "u2"));
        when(aclEnforcer.resolveScopePlan(any(), anyString()))
                .thenReturn(new AclEnforcer.ScopePlan(AclEnforcer.ScopeKind.SELF,
                        java.util.Set.of(user.userId()), null, false));
        when(aclEnforcer.filterInScope(any(), any()))
                .thenReturn(List.of(Map.of("id", "u1")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> controller.batchDeleteRecords("posts",
                List.of("u1", "u2"), user))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("A0-2 批量更新：ROW update 拒绝 → 404 且 service 未写入任何一条")
    void batchUpdate_rowDenied_noWrite() {
        CollectionService.BatchUpdateItem item =
                new CollectionService.BatchUpdateItem("u1", Map.of("title", "x"));
        when(service.getRecord("posts", "u1", "tenant_default")).thenReturn(Map.of("id", "u1"));
        when(rowAclService.evaluateUpdate(eq("tenant_default"), eq("posts"), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> controller.batchUpdateRecords("posts", List.of(item), user))
                .isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .batchUpdate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A0-2 批量删除：ROW delete 拒绝 → 404 且 service 未写入任何一条")
    void batchDelete_rowDenied_noWrite() {
        when(service.getRecord("posts", "u1", "tenant_default")).thenReturn(Map.of("id", "u1"));
        when(service.getRecord("posts", "u2", "tenant_default")).thenReturn(Map.of("id", "u2"));
        when(rowAclService.evaluateDelete(eq("tenant_default"), eq("posts"), any(), any()))
                .thenReturn(true, false);

        assertThatThrownBy(() -> controller.batchDeleteRecords("posts", List.of("u1", "u2"), user))
                .isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .batchDelete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A0-2 清空回收站：任一 ROW 拒绝 → 404 且零物理删除")
    void clearRecycle_rowDenied_noPurge() {
        when(service.listDeletedRecords("posts", "tenant_default", 500))
                .thenReturn(List.of(Map.of("id", "d1"), Map.of("id", "d2")));
        when(rowAclService.evaluateDelete(eq("tenant_default"), eq("posts"), any(), any()))
                .thenReturn(true, false);

        assertThatThrownBy(() -> controller.clearRecycleBin("posts", user))
                .isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .hardDeleteRecord(any(), any(), any());
    }

    @Test
    @DisplayName("A0-2 版本列表：ROW read 拒绝 → 404")
    void versions_rowDenied_404() {
        when(service.getRecord("posts", "u1", "tenant_default")).thenReturn(Map.of("id", "u1"));
        when(rowAclService.evaluateRead(eq("tenant_default"), eq("posts"), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> controller.listRecordVersions("posts", "u1", user))
                .isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .listRecordVersions(any(), any(), any());
    }
}
