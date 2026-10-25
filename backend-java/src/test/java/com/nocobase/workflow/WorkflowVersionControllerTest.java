package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-410: 工作流版本管理 API 测试。
 * 覆盖:
 *   - GET  /api/workflows/{id}/versions            版本列表
 *   - POST /api/workflows/{id}/versions/publish   发布新版本
 *   - POST /api/workflows/{id}/versions/{v}/rollback  回滚
 */
class WorkflowVersionControllerTest {

    private WorkflowRepository workflowRepository;
    private WorkflowVersionRepository versionRepository;
    private WorkflowGraphValidator graphValidator;
    private AuditService auditService;
    private WorkflowVersionController controller;
    private AuthenticatedUser testUser;
    private UUID workflowId;
    private String tenantId;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        versionRepository = mock(WorkflowVersionRepository.class);
        graphValidator = mock(WorkflowGraphValidator.class);
        auditService = mock(AuditService.class);
        controller = new WorkflowVersionController(
                workflowRepository, versionRepository, graphValidator, new ObjectMapper(), auditService
        );

        testUser = new AuthenticatedUser(UUID.randomUUID(), "alice", "tenant_default");
        tenantId = testUser.tenantId();
        workflowId = UUID.randomUUID();

        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(testUser, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER")))));

        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    private WorkflowEntity workflow() {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(workflowId);
        w.setTenantId(tenantId);
        w.setName("Test Flow");
        w.setTitle("测试工作流");
        w.setDescription("Test description");
        w.setTriggerJson("{\"type\":\"record.created\"}");
        w.setNodesJson("[{\"id\":\"n1\",\"type\":\"start\"}]");
        w.setEdgesJson("[{\"source\":\"n1\",\"target\":\"n2\"}]");
        return w;
    }

    @Test
    void list_returnsVersionsInDescendingOrder() {
        WorkflowEntity w = workflow();
        when(workflowRepository.findByIdAndTenantId(eq(workflowId), eq(tenantId)))
                .thenReturn(Optional.of(w));

        UUID v1Id = UUID.randomUUID();
        UUID v2Id = UUID.randomUUID();
        WorkflowVersionEntity v1 = new WorkflowVersionEntity();
        v1.setId(v1Id);
        v1.setWorkflowId(workflowId);
        v1.setTenantId(tenantId);
        v1.setVersion(1);
        v1.setDefinitionJson("{\"name\":\"v1\"}");
        v1.setPublishedBy(testUser.userId());
        v1.setPublishedAt(Instant.now().minusSeconds(10));

        WorkflowVersionEntity v2 = new WorkflowVersionEntity();
        v2.setId(v2Id);
        v2.setWorkflowId(workflowId);
        v2.setTenantId(tenantId);
        v2.setVersion(2);
        v2.setDefinitionJson("{\"name\":\"v2\"}");
        v2.setPublishedBy(testUser.userId());
        v2.setPublishedAt(Instant.now());

        when(versionRepository.findByWorkflowIdAndTenantIdOrderByVersionDesc(eq(workflowId), eq(tenantId)))
                .thenReturn(List.of(v2, v1));
        when(versionRepository.findMaxVersionByWorkflowIdAndTenantId(eq(workflowId), eq(tenantId)))
                .thenReturn(2);

        Map<String, Object> result = controller.list(workflowId);

        assertEquals(0, result.get("code"));
        assertNotNull(result.get("data"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) ((Map<?, ?>) result.get("data")).get("versions");
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).get("version"));
        assertEquals(1, versions.get(1).get("version"));
    }

    @Test
    void publish_createsNewVersionWithIncrementedNumber() {
        WorkflowEntity w = workflow();
        when(workflowRepository.findByIdAndTenantId(eq(workflowId), eq(tenantId)))
                .thenReturn(Optional.of(w));
        when(versionRepository.findMaxVersionByWorkflowIdAndTenantId(eq(workflowId), eq(tenantId)))
                .thenReturn(2);
        when(versionRepository.save(any(WorkflowVersionEntity.class))).thenAnswer(inv -> {
            WorkflowVersionEntity v = inv.getArgument(0);
            v.setId(UUID.randomUUID());
            return v;
        });

        ResponseEntity<Map<String, Object>> response = controller.publish(workflowId, testUser);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(0, body.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertEquals(3, data.get("version"));
        assertNotNull(data.get("workflow_id"));
        assertNotNull(data.get("published_at"));
    }

    @Test
    void rollback_replacesCurrentDefinitionWithSnapshot() {
        WorkflowEntity w = workflow();
        UUID versionId = UUID.randomUUID();
        String snapshotJson = "{\"name\":\"RolledBack\",\"title\":\"回滚标题\",\"description\":\"旧版\",\"trigger\":{\"type\":\"manual\"},\"nodes\":[{\"id\":\"n1\",\"type\":\"start\"}],\"edges\":[]}";

        WorkflowVersionEntity v = new WorkflowVersionEntity();
        v.setId(versionId);
        v.setWorkflowId(workflowId);
        v.setTenantId(tenantId);
        v.setVersion(1);
        v.setDefinitionJson(snapshotJson);
        v.setPublishedBy(testUser.userId());
        v.setPublishedAt(Instant.now().minusSeconds(60));

        when(workflowRepository.findByIdAndTenantId(eq(workflowId), eq(tenantId)))
                .thenReturn(Optional.of(w));
        when(versionRepository.findByWorkflowIdAndVersionAndTenantId(eq(workflowId), eq(1), eq(tenantId)))
                .thenReturn(Optional.of(v));
        doNothing().when(graphValidator).validate(any(List.class), any(List.class));

        Map<String, Object> result = controller.rollback(workflowId, 1, testUser);

        assertEquals(0, result.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(1, data.get("rolled_back_to"));
        assertEquals("RolledBack", w.getName());
        assertEquals("回滚标题", w.getTitle());
    }

    @Test
    void rollback_whenVersionNotFound_throwsNotFound() {
        WorkflowEntity w = workflow();
        when(workflowRepository.findByIdAndTenantId(eq(workflowId), eq(tenantId)))
                .thenReturn(Optional.of(w));
        when(versionRepository.findByWorkflowIdAndVersionAndTenantId(eq(workflowId), eq(99), eq(tenantId)))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.rollback(workflowId, 99, testUser)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void rollback_whenGraphInvalid_throwsBadRequest() {
        WorkflowEntity w = workflow();
        String badSnapshot = "{\"name\":\"Bad\",\"nodes\":[{\"id\":\"n1\"}],\"edges\":[{\"source\":\"n2\",\"target\":\"n3\"}]}";

        WorkflowVersionEntity v = new WorkflowVersionEntity();
        v.setId(UUID.randomUUID());
        v.setWorkflowId(workflowId);
        v.setTenantId(tenantId);
        v.setVersion(1);
        v.setDefinitionJson(badSnapshot);
        v.setPublishedBy(testUser.userId());
        v.setPublishedAt(Instant.now());

        when(workflowRepository.findByIdAndTenantId(eq(workflowId), eq(tenantId)))
                .thenReturn(Optional.of(w));
        when(versionRepository.findByWorkflowIdAndVersionAndTenantId(eq(workflowId), eq(1), eq(tenantId)))
                .thenReturn(Optional.of(v));
        doThrow(new WorkflowGraphValidationException("断点不存在"))
                .when(graphValidator).validate(any(List.class), any(List.class));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.rollback(workflowId, 1, testUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
