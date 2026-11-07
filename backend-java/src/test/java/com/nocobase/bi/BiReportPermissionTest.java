package com.nocobase.bi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.AclEnforcer;
import com.nocobase.auth.AclPolicyEntity;
import com.nocobase.meta.CollectionService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

/** R1d：BI 四个数据端点的权限闭环。 */
class BiReportPermissionTest {

    private CollectionService collectionService;
    private AclEnforcer aclEnforcer;
    private com.nocobase.acl.RowAclService rowAclService;
    private com.nocobase.auth.RoleRepository roleRepository;
    private BiReportService service;
    private final String tenant = "tenant_a";
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        collectionService = mock(CollectionService.class);
        aclEnforcer = mock(AclEnforcer.class);
        rowAclService = mock(com.nocobase.acl.RowAclService.class);
        roleRepository = mock(com.nocobase.auth.RoleRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new BiReportService(collectionService, mock(BiReportRepository.class),
                new ObjectMapper(), redis,
                new com.nocobase.bi.export.ReportExcelExporter(),
                new com.nocobase.bi.export.ReportPdfExporter(),
                aclEnforcer, rowAclService, roleRepository);
        when(aclEnforcer.resolveScopePlan(any(), any()))
                .thenReturn(new AclEnforcer.ScopePlan(AclEnforcer.ScopeKind.ALL, null, null, false));
        when(aclEnforcer.filterReadableFields(any(), any(), any())).thenReturn(null);
        when(rowAclService.hasPolicies(any(), any(), any())).thenReturn(false);
        when(rowAclService.filterReadable(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(roleRepository.findRoleNamesByUserId(any(), any())).thenReturn(List.of("USER"));
        when(aclEnforcer.filterRecord(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(3));
    }

    @Test
    @DisplayName("无权集合的 pivot → 403")
    void pivot_withoutRead_forbidden() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "无权限"))
                .when(aclEnforcer).assertCan(eq(user), eq(tenant), eq("orders"),
                        eq(AclPolicyEntity.Action.READ));

        assertThatThrownBy(() -> service.executePivot("orders", tenant,
                List.of("status"), List.of(), List.of(Map.of("field", "amount", "agg", "SUM")),
                List.of(), user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));
        verify(collectionService, never()).aggregate(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("聚合请求不可读字段 → 403（不静默替换）")
    void pivot_hiddenMeasure_forbidden() {
        when(aclEnforcer.filterReadableFields(user, tenant, "orders"))
                .thenReturn(Set.of("salary"));

        assertThatThrownBy(() -> service.executePivot("orders", tenant,
                List.of("status"), List.of(),
                List.of(Map.of("field", "salary", "agg", "SUM")), List.of(), user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    @DisplayName("chart 不可读 y 字段 → 403")
    void chart_hiddenField_forbidden() {
        when(aclEnforcer.filterReadableFields(user, tenant, "sales"))
                .thenReturn(Set.of("amount"));

        assertThatThrownBy(() -> service.executeChart("sales", tenant, "bar",
                "month", "amount", null, "SUM", List.of(), user))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("扁平导出：数据范围谓词下推 + 字段级裁剪")
    void listFlatRecords_appliesScopeAndFieldFilter() {
        AclEnforcer.SqlPredicate predicate =
                new AclEnforcer.SqlPredicate("created_by = ?", List.of(user));
        when(aclEnforcer.resolveScopePlan(user, tenant))
                .thenReturn(new AclEnforcer.ScopePlan(AclEnforcer.ScopeKind.SELF,
                        Set.of(user), predicate, false));
        when(collectionService.listRecords(eq("orders"), eq(tenant), eq(1000), any(), any(),
                eq("created_by = ?"), any())).thenReturn(List.of(Map.of("id", "r1")));
        when(aclEnforcer.filterInScope(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(aclEnforcer.filterRecord(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(3));

        List<Map<String, Object>> records = service.listFlatRecords("orders", tenant,
                List.of(), user);

        assertThat(records).hasSize(1);
        verify(collectionService).listRecords(eq("orders"), eq(tenant), eq(1000), any(), any(),
                eq("created_by = ?"), any());
        verify(aclEnforcer).filterRecord(eq(user), eq(tenant), eq("orders"), any());
    }

    @Test
    @DisplayName("保存报表导出：不可读字段被剔除而非整页失败（R-8）")
    void savedReportExport_dropsHiddenFields() {
        UUID reportId = UUID.randomUUID();
        BiReportEntity e = new BiReportEntity();
        e.setId(reportId);
        e.setTenantId(tenant);
        e.setCollectionName("orders");
        e.setName("报表");
        e.setType(BiReportEntity.Type.PIVOT);
        e.setConfigJson("{\"rows\":[\"status\",\"secret\"],\"values\":[{\"field\":\"amount\",\"agg\":\"SUM\"},{\"field\":\"salary\",\"agg\":\"SUM\"}]}");
        when(((BiReportRepository) serviceRepository()).findByIdAndTenantId(reportId, tenant))
                .thenReturn(java.util.Optional.of(e));
        when(aclEnforcer.filterReadableFields(user, tenant, "orders"))
                .thenReturn(Set.of("secret", "salary"));
        when(collectionService.aggregate(eq("orders"), eq(tenant), any(), any(), any()))
                .thenReturn(List.of());
        // 只剩 amount，仍可聚合（剔除 secret/salary）
        assertThat(service.exportToExcel(reportId, tenant, user)).isNotNull();
    }

    private Object serviceRepository() {
        // 反射取私有 reportRepository，避免为了单测再加构造参数
        return org.springframework.test.util.ReflectionTestUtils.getField(service, "reportRepository");
    }

    @Test
    @DisplayName("A0-1 ROW read 策略存在 → 聚合显式 403，不返回未裁剪统计")
    void pivot_rowPolicy_forbidden() {
        when(rowAclService.hasPolicies(tenant, "orders", "read")).thenReturn(true);
        when(aclEnforcer.filterReadableFields(user, tenant, "orders")).thenReturn(null);
        when(aclEnforcer.resolveScopePlan(user, tenant))
                .thenReturn(new AclEnforcer.ScopePlan(
                        AclEnforcer.ScopeKind.ALL, null, null, false));

        assertThatThrownBy(() -> service.executePivot("orders", tenant,
                List.of("status"), List.of(),
                List.of(Map.of("field", "amount", "agg", "SUM")), List.of(), user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    @DisplayName("A0-1 CUSTOM 数据范围 → 聚合 403")
    void pivot_customScope_forbidden() {
        when(aclEnforcer.resolveScopePlan(user, tenant))
                .thenReturn(new AclEnforcer.ScopePlan(
                        AclEnforcer.ScopeKind.CUSTOM, null, null, true));

        assertThatThrownBy(() -> service.executePivot("orders", tenant,
                List.of("status"), List.of(),
                List.of(Map.of("field", "amount", "agg", "SUM")), List.of(), user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    @DisplayName("A0-1 BI 明细必须应用 ROW read 策略")
    void listFlatRecords_appliesRowAcl() {
        when(collectionService.listRecords(eq("orders"), eq(tenant), eq(1000), any(), any()))
                .thenReturn(List.of(Map.of("id", "r1"), Map.of("id", "r2")));
        when(rowAclService.filterReadable(eq(tenant), eq("orders"), any(), any()))
                .thenReturn(List.of(Map.of("id", "r1")));

        List<Map<String, Object>> records = service.listFlatRecords("orders", tenant,
                List.of(), user);

        assertThat(records).extracting(m -> m.get("id")).containsExactly("r1");
        verify(rowAclService).filterReadable(eq(tenant), eq("orders"), any(), any());
    }
}
