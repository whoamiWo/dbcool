package com.nocobase.integration.datasource;

import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.audit.AuditService;
import com.nocobase.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PHASE B B3: 外部数据源管理 REST API。
 *
 * <p>端点（tenant 隔离由请求上下文保证，此处不显式传 tenantId —— 后续可按需补充）：
 * <ul>
 *   <li>{@code GET    /api/datasources}                 列出</li>
 *   <li>{@code GET    /api/datasources/{id}}            详情</li>
 *   <li>{@code POST   /api/datasources/{id}/test}       连接测试</li>
 *   <li>{@code GET    /api/datasources/{id}/tables}     列出外部表</li>
 *   <li>{@code GET    /api/datasources/{id}/tables/{tableName}/columns}  表结构</li>
 *   <li>{@code POST   /api/datasources/{id}/collections} 创建虚拟 Collection</li>
 *   <li>{@code DELETE /api/datasources/{id}}            删除数据源</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/datasources")
public class ExternalDataSourceController {

    private final ExternalDataSourceService service;
    private final AuditService auditService;

    public ExternalDataSourceController(ExternalDataSourceService service, AuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    /** 创建连接请求体 */
    public record CreateDataSourceRequest(
            @NotBlank String name,
            @NotBlank String vendor,
            @NotBlank String host,
            @NotNull Integer port,
            @NotBlank String databaseName,
            @NotBlank String username,
            @NotBlank String password,
            /** 可选：显式 JDBC 驱动类名（不填则按 vendor 推断） */
            String driverClassName) {}

    /** 创建虚拟 Collection 请求体 */
    public record CreateVirtualCollectionRequest(
            @NotBlank String tableName,
            @NotBlank String collectionName,
            String title,
            String description) {}

        @PreAuthorize("isAuthenticated()")
@GetMapping
    public Map<String, Object> list() {
        String tenantId = TenantContext.requireTenantId();
        List<ExternalDataSourceEntity> list = service.listDataSources(tenantId);
        return Map.of("code", 0, "message", "ok",
                "data", list.stream().map(this::toView).toList());
    }

    /**
     * 创建外部数据源（B3 返工：修复前无此端点，CreateDataSourceRequest 是死 record，
     * 数据源只能靠手工插密文入库）。
     *
     * <p>Body: {@code {name, vendor, host, port, databaseName, username, password, driverClassName?}}
     * 密码在 Service 内加密存储，响应不返回任何密码字段（复用 toView）。
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateDataSourceRequest body) {
        String tenantId = TenantContext.requireTenantId();
        ExternalDataSourceEntity saved = service.createDataSource(
                tenantId, body.name(), body.vendor(), body.host(), body.port(),
                body.databaseName(), body.username(), body.password(),
                body.driverClassName(), null);
        auditService.log(tenantId, null, null, "CREATE_DATASOURCE", "datasource/create",
                String.valueOf(saved.getId()), Map.of("name", body.name(), "vendor", body.vendor()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "ok", "data", toView(saved)));
    }

        @PreAuthorize("isAuthenticated()")
@GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        String tenantId = TenantContext.requireTenantId();
        return service.getDataSource(id, tenantId)
                .map(d -> {
                    Map<String, Object> view = toView(d);
                    return Map.of("code", 0, "message", "ok", "data", view);
                })
                .orElseGet(() -> Map.of("code", 404, "message", "外部数据源不存在"));
    }

        @PreAuthorize("isAuthenticated()")
@PostMapping("/{id}/test")
    public Map<String, Object> testConnection(@PathVariable UUID id) {
        String tenantId = TenantContext.requireTenantId();
        auditService.log(tenantId, null, null, "TEST_DATASOURCE", "datasource/test", String.valueOf(id), null);
        boolean ok = service.testConnection(id, tenantId);
        return Map.of("code", 0, "message", "ok", "data", Map.of("connected", ok));
    }

        @PreAuthorize("isAuthenticated()")
@GetMapping("/{id}/tables")
    public Map<String, Object> listTables(@PathVariable UUID id) {
        String tenantId = TenantContext.requireTenantId();
        List<String> tables = service.listTables(id, tenantId);
        return Map.of("code", 0, "message", "ok", "data", tables);
    }

        @PreAuthorize("isAuthenticated()")
@GetMapping("/{id}/tables/{tableName}/columns")
    public Map<String, Object> readColumns(@PathVariable UUID id, @PathVariable String tableName) {
        String tenantId = TenantContext.requireTenantId();
        List<com.nocobase.meta.FieldDef> columns = service.readTableColumns(id, tenantId, tableName);
        return Map.of("code", 0, "message", "ok", "data", columns);
    }

        @PreAuthorize("isAuthenticated()")
@PostMapping("/{id}/collections")
    public ResponseEntity<Map<String, Object>> createVirtualCollection(
            @PathVariable UUID id,
            @Valid @RequestBody CreateVirtualCollectionRequest body) {
        String tenantId = TenantContext.requireTenantId();
        CollectionMetaEntity meta = service.createVirtualCollection(
                id, tenantId, body.tableName(), body.collectionName(),
                body.title(), body.description(), null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "ok", "data", meta));
    }

        @PreAuthorize("isAuthenticated()")
@DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        String tenantId = TenantContext.requireTenantId();
        auditService.log(tenantId, null, null, "DELETE_DATASOURCE", "datasource/delete", String.valueOf(id), null);
        service.deleteDataSource(id, tenantId);
        return Map.of("code", 0, "message", "ok");
    }

    /** 实体 → 视图（密码不返回明文/密文） */
    private Map<String, Object> toView(ExternalDataSourceEntity d) {
        return Map.of(
                "id", d.getId(),
                "name", d.getName(),
                "vendor", d.getVendor(),
                "host", d.getHost(),
                "port", d.getPort(),
                "databaseName", d.getDatabaseName(),
                "username", d.getUsername(),
                "status", d.getStatus(),
                "lastSyncedAt", d.getLastSyncedAt() == null ? "" : d.getLastSyncedAt().toString(),
                "createdAt", d.getCreatedAt() == null ? "" : d.getCreatedAt().toString());
    }
}
