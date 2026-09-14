package com.nocobase.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.acl.RowAclService;
import com.nocobase.auth.AclEnforcer;
import com.nocobase.auth.AclPolicyEntity;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.RoleRepository;
import com.nocobase.audit.AuditService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ResponseStatusException;

/**
 * CollectionController 单测(Week 28).
 * 覆盖 15+ endpoints(create / list / get / update / delete / fields / records / csv).
 */
class CollectionControllerTest {

    private CollectionService service;
    private AsyncMigrationService migrationService;
    private MigrationJobRepository jobRepository;
    private AclEnforcer aclEnforcer;
    private AuditService auditService;
    private RowAclService rowAclService;
    private RoleRepository roleRepository;
    private CollectionController controller;
    private ObjectMapper json = new ObjectMapper();

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

        controller = new CollectionController(service, migrationService, jobRepository,
                aclEnforcer, auditService, rowAclService, roleRepository);

        // 默认 ACL 全通过、ROW ACL 全允许
        doNothing().when(aclEnforcer).assertCan(any(), anyString(), anyString(), any());
        doNothing().when(aclEnforcer).assertCanWriteFields(any(), anyString(), anyString(), any(), any());
        when(aclEnforcer.filterRecord(any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(3));
        when(rowAclService.filterReadable(anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(rowAclService.evaluateRead(anyString(), anyString(), any(), any())).thenReturn(true);
        when(rowAclService.evaluateUpdate(anyString(), anyString(), any(), any())).thenReturn(true);
        when(rowAclService.evaluateDelete(anyString(), anyString(), any(), any())).thenReturn(true);
        when(roleRepository.findRoleNamesByUserId(any(), anyString())).thenReturn(List.of("admin"));

        testUser = new AuthenticatedUser(
                UUID.randomUUID(), "alice", "tenant_default");
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(testUser, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER")))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CollectionMetaEntity makeMeta(String name) {
        CollectionMetaEntity m = new CollectionMetaEntity();
        m.setId(UUID.randomUUID());
        m.setName(name);
        m.setTitle(name);
        m.setDescription("");
        m.setFieldsJson("[{\"name\":\"x\",\"type\":\"text\"}]");
        m.setTenantId("tenant_default");
        m.setCreatedAt(Instant.now());
        m.setCreatedBy(testUser.userId());
        return m;
    }

    // ============ Collection CRUD ============

    @Test
    void create_returns201() {
        when(service.create(anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(makeMeta("posts"));

        ResponseEntity<Map<String, Object>> resp = controller.create(
                new CollectionController.CreateCollectionRequest(
                        "posts", "Posts", "blog posts", List.of()),
                testUser);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals(0, resp.getBody().get("code"));
        assertNotNull(resp.getBody().get("data"));
    }

    @Test
    void list_returnsAllCollections() {
        when(service.list("tenant_default")).thenReturn(List.of(makeMeta("a"), makeMeta("b")));

        Map<String, Object> resp = controller.list(testUser);

        assertEquals(0, resp.get("code"));
        assertEquals(2, ((List<?>) resp.get("data")).size());
    }

    @Test
    void get_includesParsedFields() {
        CollectionMetaEntity meta = makeMeta("posts");
        when(service.get("posts")).thenReturn(meta);
        when(service.parseFields(meta)).thenReturn(List.of(
                new FieldDef("x", "text", false, "X", null)));

        Map<String, Object> resp = controller.get("posts");

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> dto = (Map<String, Object>) resp.get("data");
        assertEquals("posts", dto.get("name"));
        assertEquals(1, ((List<?>) dto.get("fields")).size());
    }

    @Test
    void update_returnsUpdatedMeta() {
        when(service.updateMeta("posts", "New", "d", "tenant_default"))
                .thenReturn(makeMeta("posts"));

        Map<String, Object> resp = controller.update("posts",
                new CollectionController.UpdateCollectionRequest("New", "d"), testUser);

        assertEquals(0, resp.get("code"));
    }

    @Test
    void delete_crossTenant_returns403() {
        // Week 41 B3:controller 直接调 service.deleteMeta,跨租户校验在 service 内
        // 模拟 service.deleteMeta 抛 FORBIDDEN
        when(service.deleteMeta(eq("posts"), eq(testUser.tenantId())))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权删除该 collection"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.delete("posts", testUser));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void delete_sameTenant_succeeds() {
        when(service.get("posts")).thenReturn(makeMeta("posts"));
        // Week 41 B3:deleteMeta 需要 mock 返回成功
        when(service.deleteMeta(eq("posts"), eq(testUser.tenantId()))).thenReturn(true);

        Map<String, Object> resp = controller.delete("posts", testUser);

        assertEquals(0, resp.get("code"));
        assertEquals("deleted", resp.get("message"));
    }

    // ============ Fields ============

    @Test
    void addField_async_returns202() {
        UUID jobId = UUID.randomUUID();
        when(service.addField(eq("posts"), any(), eq("tenant_default"), any()))
                .thenReturn(jobId);

        ResponseEntity<Map<String, Object>> resp = controller.addField(
                "posts", new FieldDef("title", "text", false, "T", null), testUser);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertEquals(true, data.get("async"));
        assertEquals(jobId.toString(), data.get("job_id"));
    }

    @Test
    void addField_sync_returns200() {
        when(service.addField(eq("posts"), any(), eq("tenant_default"), any()))
                .thenReturn(null);  // null = sync success

        ResponseEntity<Map<String, Object>> resp = controller.addField(
                "posts", new FieldDef("title", "text", false, "T", null), testUser);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertEquals(false, data.get("async"));
    }

    @Test
    void removeField_returns202WithJobId() {
        UUID jobId = UUID.randomUUID();
        when(service.removeField("posts", "old_field", "tenant_default", testUser.userId()))
                .thenReturn(jobId);

        ResponseEntity<Map<String, Object>> resp = controller.removeField(
                "posts", "old_field", testUser);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
    }

    @Test
    void renameField_returns202WithJobId() {
        UUID jobId = UUID.randomUUID();
        when(service.renameField("posts", "old", "new", "tenant_default", testUser.userId()))
                .thenReturn(jobId);

        ResponseEntity<Map<String, Object>> resp = controller.renameField("posts", "old",
                new CollectionController.RenameFieldRequest("new"), testUser);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
    }

    // ============ getJob ============

    @Test
    void getJob_returnsJob() {
        UUID jobId = UUID.randomUUID();
        MigrationJobEntity job = new MigrationJobEntity();
        job.setId(jobId);
        job.setCollectionName("posts");
        job.setOperation(MigrationJobEntity.Operation.ADD_FIELD);
        job.setStatus(MigrationJobEntity.Status.RUNNING);
        job.setCreatedAt(Instant.now());
        job.setStartedAt(Instant.now());
        job.setFinishedAt(Instant.now());
        when(migrationService.getJob(jobId)).thenReturn(job);

        Map<String, Object> resp = controller.getJob(jobId);

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals("RUNNING", data.get("status"));
    }

    @Test
    void getJob_notFound_returns404() {
        UUID jobId = UUID.randomUUID();
        when(migrationService.getJob(jobId)).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getJob(jobId));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ============ Records ============

    @Test
    void createRecord_returns201AndAudit() {
        UUID id = UUID.randomUUID();
        when(service.insertRecord(eq("posts"), any(), eq("tenant_default"))).thenReturn(id);

        ResponseEntity<Map<String, Object>> resp = controller.createRecord("posts",
                Map.of("title", "Hi"), testUser);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        verify(auditService).log(eq("tenant_default"), eq(testUser.userId()),
                eq("alice"), eq("CREATE"), eq("posts"), eq(id.toString()), any());
    }

    @Test
    void listRecords_withFilter_returnsServiceResult() {
        when(service.listRecords(eq("posts"), eq("tenant_default"), eq(50),
                any(), any())).thenReturn(List.of(Map.of("title", "A")));

        Map<String, Object> resp = controller.listRecords("posts", 50, null, null, testUser);

        assertEquals(0, resp.get("code"));
        assertEquals(1, ((List<?>) resp.get("data")).size());
    }

    @Test
    void listRecords_withComplexFilter_parsesCorrectly() {
        when(service.listRecords(eq("posts"), eq("tenant_default"), eq(50),
                any(), any())).thenReturn(List.of());

        // filter=name:contains:Al,age:gt:20,email:empty
        controller.listRecords("posts", 50, null,
                "name:contains:Al,age:gt:20,email:empty", testUser);

        ArgumentCaptor<List<CollectionService.FilterRule>> cap =
                ArgumentCaptor.forClass(List.class);
        verify(service).listRecords(eq("posts"), eq("tenant_default"), eq(50),
                any(), cap.capture());
        List<CollectionService.FilterRule> rules = cap.getValue();
        assertEquals(3, rules.size());
        assertEquals("contains", rules.get(0).op());
        assertEquals("gt", rules.get(1).op());
        assertEquals("empty", rules.get(2).op());
    }

    @Test
    void listRecords_invalidFilterSkipped_parsesValid() {
        when(service.listRecords(eq("posts"), eq("tenant_default"), anyInt(),
                any(), any())).thenReturn(List.of());

        // 非法 op:invalid + 合法 eq:Alice
        controller.listRecords("posts", 50, null, "op:invalid:x,name:eq:Alice", testUser);

        ArgumentCaptor<List<CollectionService.FilterRule>> cap =
                ArgumentCaptor.forClass(List.class);
        verify(service).listRecords(eq("posts"), eq("tenant_default"), anyInt(),
                any(), cap.capture());
        // 只有 eq:Alice 通过
        assertEquals(1, cap.getValue().size());
        assertEquals("name", cap.getValue().get(0).field());
    }

    @Test
    void getRecord_returnsRecord() {
        Map<String, Object> record = Map.of("id", "rec-1", "title", "Hello");
        when(service.getRecord("posts", "rec-1", "tenant_default")).thenReturn(record);

        Map<String, Object> resp = controller.getRecord("posts", "rec-1", testUser);

        assertEquals(0, resp.get("code"));
    }

    @Test
    void getRecord_rowAclDeny_returns404() {
        Map<String, Object> record = Map.of("id", "rec-1");
        when(service.getRecord("posts", "rec-1", "tenant_default")).thenReturn(record);
        when(rowAclService.evaluateRead(anyString(), anyString(), any(), any())).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getRecord("posts", "rec-1", testUser));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void updateRecord_succeedsAndAudits() {
        Map<String, Object> existing = Map.of("id", "rec-1", "title", "Old");
        when(service.getRecord("posts", "rec-1", "tenant_default")).thenReturn(existing);
        when(service.updateRecord(eq("posts"), eq("rec-1"), any(), eq("tenant_default")))
                .thenReturn(true);

        Map<String, Object> resp = controller.updateRecord("posts", "rec-1",
                Map.of("title", "New"), testUser);

        assertEquals(0, resp.get("code"));
        verify(auditService).log(eq("tenant_default"), eq(testUser.userId()),
                eq("alice"), eq("UPDATE"), eq("posts"), eq("rec-1"), any());
    }

    @Test
    void updateRecord_notFound_returns404() {
        Map<String, Object> existing = Map.of("id", "rec-1");
        when(service.getRecord("posts", "rec-1", "tenant_default")).thenReturn(existing);
        when(service.updateRecord(anyString(), anyString(), any(), anyString()))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateRecord("posts", "rec-1",
                        Map.of("title", "New"), testUser));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void updateRecord_rowAclDeny_returns403() {
        Map<String, Object> existing = Map.of("id", "rec-1");
        when(service.getRecord("posts", "rec-1", "tenant_default")).thenReturn(existing);
        when(rowAclService.evaluateUpdate(anyString(), anyString(), any(), any()))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateRecord("posts", "rec-1",
                        Map.of("title", "New"), testUser));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void deleteRecord_succeedsAndAudits() {
        Map<String, Object> existing = Map.of("id", "rec-1");
        when(service.getRecord("posts", "rec-1", "tenant_default")).thenReturn(existing);
        when(service.deleteRecord("posts", "rec-1", "tenant_default")).thenReturn(true);

        Map<String, Object> resp = controller.deleteRecord("posts", "rec-1", testUser);

        assertEquals(0, resp.get("code"));
        verify(auditService).log(eq("tenant_default"), eq(testUser.userId()),
                eq("alice"), eq("DELETE"), eq("posts"), eq("rec-1"), eq(existing));
    }

    @Test
    void deleteRecord_notFound_returns404() {
        Map<String, Object> existing = Map.of("id", "rec-1");
        when(service.getRecord("posts", "rec-1", "tenant_default")).thenReturn(existing);
        when(service.deleteRecord(anyString(), anyString(), anyString())).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteRecord("posts", "rec-1", testUser));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void deleteRecord_rowAclDeny_returns403() {
        Map<String, Object> existing = Map.of("id", "rec-1");
        when(service.getRecord("posts", "rec-1", "tenant_default")).thenReturn(existing);
        when(rowAclService.evaluateDelete(anyString(), anyString(), any(), any()))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteRecord("posts", "rec-1", testUser));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ============ CSV ============

    @Test
    void exportCsv_returnsCsvBody() {
        CollectionMetaEntity meta = makeMeta("posts");
        meta.setFieldsJson("[{\"name\":\"title\"}]");
        when(service.get("posts")).thenReturn(meta);
        when(service.listRecords("posts", "tenant_default", 1000))
                .thenReturn(List.of(Map.of("title", "Hello, World")));

        ResponseEntity<String> resp = controller.exportCsv("posts", 1000, testUser);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("title"));
        assertTrue(resp.getBody().contains("\"Hello, World\""));  // CSV escape
    }

    @Test
    void importCsv_succeedsAndCountsRows() {
        String csv = "title,body\nHello,World\nFoo,Bar\n";
        MockMultipartFile file = new MockMultipartFile("file", "data.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        when(service.insertRecord(eq("posts"), any(), eq("tenant_default")))
                .thenReturn(UUID.randomUUID());

        Map<String, Object> resp = controller.importCsv("posts", file, testUser);

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(2, data.get("total"));
        assertEquals(2, data.get("success"));
        assertEquals(0, data.get("failed"));
    }

    @Test
    void importCsv_emptyFile_returns400() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.csv",
                "text/csv", new byte[0]);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.importCsv("posts", file, testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void importCsv_blankHeader_returns400() {
        MockMultipartFile file = new MockMultipartFile("file", "bad.csv",
                "text/csv", "\n".getBytes(StandardCharsets.UTF_8));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.importCsv("posts", file, testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void importCsv_someRowsFail_recordsErrors() {
        String csv = "title,body\nFoo,Bar\nBad,Row\n";
        MockMultipartFile file = new MockMultipartFile("file", "data.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        // 第一次调用成功,第二次失败
        when(service.insertRecord(eq("posts"), any(), eq("tenant_default")))
                .thenReturn(UUID.randomUUID())
                .thenThrow(new RuntimeException("validation failed"));

        Map<String, Object> resp = controller.importCsv("posts", file, testUser);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(2, data.get("total"));
        assertEquals(1, data.get("success"));
        assertEquals(1, data.get("failed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) data.get("errors");
        assertEquals(1, errors.size());
        assertEquals(3, errors.get(0).get("row"));  // row 3 (header + 2 rows, last fails)
    }
}
