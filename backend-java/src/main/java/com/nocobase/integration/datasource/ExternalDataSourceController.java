package com.nocobase.integration.datasource;

import com.nocobase.meta.CollectionMetaEntity;
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

    public ExternalDataSourceController(ExternalDataSourceService service) {
        this.service = service;
    }

    /** 创建连接请求体 */
    public record CreateDataSourceRequest(
            @NotBlank String name,
            @NotBlank String vendor,
            @NotBlank String host,
            @NotNull Integer port,
            @NotBlank String databaseName,
            @NotBlank String username,
            @NotBlank String password) {}

    /** 创建虚拟 Collection 请求体 */
    public record CreateVirtualCollectionRequest(
            @NotBlank String tableName,
            @NotBlank String collectionName,
            String title,
            String description) {}

    @GetMapping
    public Map<String, Object> list() {
        // 当前按 "system" 租户上下文占位；实际接入租户鉴权后替换
        List<ExternalDataSourceEntity> list = service.listDataSources("system");
        return Map.of("code", 0, "message", "ok",
                "data", list.stream().map(this::toView).toList());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return service.getDataSource(id, "system")
                .map(d -> {
                    Map<String, Object> view = toView(d);
                    return Map.of("code", 0, "message", "ok", "data", view);
                })
                .orElseGet(() -> Map.of("code", 404, "message", "外部数据源不存在"));
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> testConnection(@PathVariable UUID id) {
        boolean ok = service.testConnection(id, "system");
        return Map.of("code", 0, "message", "ok", "data", Map.of("connected", ok));
    }

    @GetMapping("/{id}/tables")
    public Map<String, Object> listTables(@PathVariable UUID id) {
        List<String> tables = service.listTables(id, "system");
        return Map.of("code", 0, "message", "ok", "data", tables);
    }

    @GetMapping("/{id}/tables/{tableName}/columns")
    public Map<String, Object> readColumns(@PathVariable UUID id, @PathVariable String tableName) {
        List<com.nocobase.meta.FieldDef> columns = service.readTableColumns(id, "system", tableName);
        return Map.of("code", 0, "message", "ok", "data", columns);
    }

    @PostMapping("/{id}/collections")
    public ResponseEntity<Map<String, Object>> createVirtualCollection(
            @PathVariable UUID id,
            @Valid @RequestBody CreateVirtualCollectionRequest body) {
        CollectionMetaEntity meta = service.createVirtualCollection(
                id, "system", body.tableName(), body.collectionName(),
                body.title(), body.description(), null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "ok", "data", meta));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        service.deleteDataSource(id, "system");
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
