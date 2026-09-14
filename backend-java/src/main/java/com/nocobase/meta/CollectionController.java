package com.nocobase.meta;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Collection REST API — Week 7 扩展支持修改表(US-005).
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Collections", description = "数据模型")
@RequestMapping("/api/collections")
public class CollectionController {

    private final CollectionService service;
    private final AsyncMigrationService migrationService;
    private final MigrationJobRepository jobRepository;
    private final com.nocobase.auth.AclEnforcer aclEnforcer;
    private final com.nocobase.audit.AuditService auditService;
    private final com.nocobase.acl.RowAclService rowAclService;
    private final com.nocobase.auth.RoleRepository roleRepository;

    public CollectionController(
            CollectionService service,
            AsyncMigrationService migrationService,
            MigrationJobRepository jobRepository,
            com.nocobase.auth.AclEnforcer aclEnforcer,
            com.nocobase.audit.AuditService auditService,
            com.nocobase.acl.RowAclService rowAclService,
            com.nocobase.auth.RoleRepository roleRepository
    ) {
        this.service = service;
        this.migrationService = migrationService;
        this.jobRepository = jobRepository;
        this.aclEnforcer = aclEnforcer;
        this.auditService = auditService;
        this.rowAclService = rowAclService;
        this.roleRepository = roleRepository;
    }

    /** 构建 RowAclService.Principal(从当前认证用户). */
    private com.nocobase.acl.RowAclService.Principal rowAclPrincipal(
            com.nocobase.auth.JwtAuthFilter.AuthenticatedUser user) {
        java.util.List<String> roles = roleRepository.findRoleNamesByUserId(
                user.userId(), user.tenantId());
        return new com.nocobase.acl.RowAclService.Principal(
                user.userId().toString(), roles);
    }

    // ============================================================
    //  Collection CRUD
    // ============================================================

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody @Valid CreateCollectionRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        CollectionMetaEntity meta = service.create(
                request.name(), request.title(), request.description(),
                request.fields(), user.tenantId(), user.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0, "message", "success",
                "data", toDto(meta)
        ));
    }

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of(
                "code", 0, "message", "success",
                "data", service.list(user.tenantId()).stream().map(this::toDto).toList()
        );
    }

    @GetMapping("/{name}")
    public Map<String, Object> get(@PathVariable String name) {
        CollectionMetaEntity meta = service.get(name);
        Map<String, Object> dto = toDto(meta);
        dto.put("fields", service.parseFields(meta));
        return Map.of("code", 0, "message", "success", "data", dto);
    }

    @PatchMapping("/{name}")
    public Map<String, Object> update(
            @PathVariable String name,
            @RequestBody UpdateCollectionRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        CollectionMetaEntity meta = service.updateMeta(name, request.title(), request.description(), user.tenantId());
        return Map.of("code", 0, "message", "success", "data", toDto(meta));
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        CollectionMetaEntity meta = service.get(name);
        if (!meta.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权删除");
        }
        return Map.of("code", 0, "message", "deleted (mark only in Week 7)", "data", Map.of());
    }

    // ============================================================
    //  Fields 管理(US-005 修改表)
    // ============================================================

    @PostMapping("/{name}/fields")
    public ResponseEntity<Map<String, Object>> addField(
            @PathVariable String name,
            @RequestBody @Valid FieldDef field,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        UUID jobId = service.addField(name, field, user.tenantId(), user.userId());
        return buildMutationResponse("add_field", field.name(), jobId);
    }

    @DeleteMapping("/{name}/fields/{fieldName}")
    public ResponseEntity<Map<String, Object>> removeField(
            @PathVariable String name,
            @PathVariable String fieldName,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        UUID jobId = service.removeField(name, fieldName, user.tenantId(), user.userId());
        return buildMutationResponse("drop_field", fieldName, jobId);
    }

    @PutMapping("/{name}/fields/{fieldName}")
    public ResponseEntity<Map<String, Object>> renameField(
            @PathVariable String name,
            @PathVariable String fieldName,
            @RequestBody @Valid RenameFieldRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        UUID jobId = service.renameField(name, fieldName, request.newName(), user.tenantId(), user.userId());
        return buildMutationResponse("rename_field", fieldName + "→" + request.newName(), jobId);
    }

    /**
     * 查 migration job 状态(异步任务轮询).
     */
    @GetMapping("/_jobs/{jobId}")
    public Map<String, Object> getJob(@PathVariable UUID jobId) {
        MigrationJobEntity job = migrationService.getJob(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job 不存在");
        }
        return Map.of("code", 0, "message", "success", "data", Map.of(
                "id", job.getId().toString(),
                "collection", job.getCollectionName(),
                "operation", job.getOperation().name(),
                "status", job.getStatus().name(),
                "error", job.getErrorMessage() != null ? job.getErrorMessage() : "",
                "created_at", job.getCreatedAt().toString(),
                "started_at", job.getStartedAt() != null ? job.getStartedAt().toString() : null,
                "finished_at", job.getFinishedAt() != null ? job.getFinishedAt().toString() : null
        ));
    }

    private ResponseEntity<Map<String, Object>> buildMutationResponse(String op, String target, UUID jobId) {
        if (jobId == null) {
            // 同步成功
            return ResponseEntity.ok(Map.of(
                    "code", 0, "message", "同步成功",
                    "data", Map.of("async", false, "operation", op, "target", target)
            ));
        } else {
            // 转异步
            return ResponseEntity.accepted().body(Map.of(
                    "code", 0, "message", "已转为异步迁移",
                    "data", Map.of(
                            "async", true,
                            "operation", op,
                            "target", target,
                            "job_id", jobId.toString(),
                            "poll_url", "/api/collections/_jobs/" + jobId
                    )
            ));
        }
    }

    // ============================================================
    //  Records(Week 5 已实现)
    // ============================================================

    @PostMapping("/{name}/records")
    public ResponseEntity<Map<String, Object>> createRecord(
            @PathVariable String name,
            @RequestBody Map<String, Object> data,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), name,
                com.nocobase.auth.AclPolicyEntity.Action.CREATE);
        // Week 16: 字段级 ACL 写拦截 — hidden 字段显式提供即拒绝
        aclEnforcer.assertCanWriteFields(user.userId(), user.tenantId(), name, data);
        UUID id = service.insertRecord(name, data, user.tenantId());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "CREATE", name, id.toString(), data);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0, "message", "success",
                "data", Map.of("id", id.toString(), "extra", data)
        ));
    }

    @GetMapping("/{name}/records")
    public Map<String, Object> listRecords(
            @PathVariable String name,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), name,
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        List<Map<String, Object>> records = service.listRecords(name, user.tenantId(), limit);
        // FIELD policy 过滤:对每条记录应用隐藏字段
        records = records.stream()
                .map(r -> aclEnforcer.filterRecord(user.userId(), user.tenantId(), name, r))
                .toList();
        // ROW ACL 过滤:每条记录过表达式策略(Week 14.5)
        records = rowAclService.filterReadable(
                user.tenantId(), name, records, rowAclPrincipal(user));
        return Map.of(
                "code", 0, "message", "success",
                "data", records
        );
    }

    // ============================================================
    //  Week 14.5 P3-3 补完:单条 get / update / delete + ROW ACL 拦截
    // ============================================================

    @GetMapping("/{name}/records/{id}")
    public Map<String, Object> getRecord(
            @PathVariable String name,
            @PathVariable String id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), name,
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        Map<String, Object> record = service.getRecord(name, id, user.tenantId());
        // ROW ACL 校验
        if (!rowAclService.evaluateRead(user.tenantId(), name, record, rowAclPrincipal(user))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或无权访问");
        }
        return Map.of(
                "code", 0, "message", "success",
                "data", aclEnforcer.filterRecord(user.userId(), user.tenantId(), name, record)
        );
    }

    @PutMapping("/{name}/records/{id}")
    public Map<String, Object> updateRecord(
            @PathVariable String name,
            @PathVariable String id,
            @RequestBody Map<String, Object> data,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), name,
                com.nocobase.auth.AclPolicyEntity.Action.UPDATE);
        // Week 16: 字段级 ACL 写拦截 — 必须在 ROW ACL 之前,避免泄露 forbidden 字段存在性
        aclEnforcer.assertCanWriteFields(user.userId(), user.tenantId(), name, data);
        // 先取旧记录做 ROW ACL 拦截评估
        Map<String, Object> existing = service.getRecord(name, id, user.tenantId());
        if (!rowAclService.evaluateUpdate(user.tenantId(), name, existing, rowAclPrincipal(user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROW ACL 拒绝:不可更新此记录");
        }
        boolean ok = service.updateRecord(name, id, data, user.tenantId());
        if (!ok) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "UPDATE", name, id, Map.of("before", existing, "after", data));
        return Map.of("code", 0, "message", "success", "data", Map.of("id", id, "extra", data));
    }

    @DeleteMapping("/{name}/records/{id}")
    public Map<String, Object> deleteRecord(
            @PathVariable String name,
            @PathVariable String id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), name,
                com.nocobase.auth.AclPolicyEntity.Action.DELETE);
        Map<String, Object> existing = service.getRecord(name, id, user.tenantId());
        if (!rowAclService.evaluateDelete(user.tenantId(), name, existing, rowAclPrincipal(user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROW ACL 拒绝:不可删除此记录");
        }
        boolean ok = service.deleteRecord(name, id, user.tenantId());
        if (!ok) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "DELETE", name, id, existing);
        return Map.of("code", 0, "message", "deleted", "data", Map.of("id", id));
    }

    // ============================================================
    //  CSV Import / Export (Week 14.5)
    // ============================================================

    @GetMapping(value = "/{name}/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> exportCsv(
            @PathVariable String name,
            @RequestParam(defaultValue = "1000") int limit,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), name,
                com.nocobase.auth.AclPolicyEntity.Action.READ);
        CollectionMetaEntity meta = service.get(name);
        List<Map<String, Object>> records = service.listRecords(name, user.tenantId(), limit);
        records = records.stream()
                .map(r -> aclEnforcer.filterRecord(user.userId(), user.tenantId(), name, r))
                .toList();
        records = rowAclService.filterReadable(
                user.tenantId(), name, records, rowAclPrincipal(user));
        // 字段顺序:fields_json 中的顺序
        List<String> headers = new java.util.ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(meta.getFieldsJson(), List.class);
            for (Map<String, Object> f : fields) {
                headers.add((String) f.get("name"));
            }
        } catch (Exception ignored) {}
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", headers)).append("\n");
        for (Map<String, Object> r : records) {
            List<String> row = new java.util.ArrayList<>();
            for (String h : headers) {
                Object v = r.get(h);
                row.add(escapeCsv(v == null ? "" : v.toString()));
            }
            csv.append(String.join(",", row)).append("\n");
        }
        String filename = name + "_" + java.time.LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(csv.toString());
    }

    @PostMapping(value = "/{name}/import", consumes = "multipart/form-data")
    public Map<String, Object> importCsv(
            @PathVariable String name,
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), name,
                com.nocobase.auth.AclPolicyEntity.Action.CREATE);
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        java.util.List<Map<String, Object>> failed = new java.util.ArrayList<>();
        int success = 0;
        int total = 0;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String headerLine = br.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV 表头为空");
            }
            String[] headers = headerLine.split(",");
            String line;
            int rowNum = 1; // 表头是 1
            while ((line = br.readLine()) != null) {
                rowNum++;
                if (line.isBlank()) continue;
                total++;
                try {
                    String[] values = parseCsvLine(line);
                    Map<String, Object> data = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < headers.length && i < values.length; i++) {
                        String v = values[i].trim();
                        if (!v.isEmpty()) data.put(headers[i].trim(), v);
                    }
                    service.insertRecord(name, data, user.tenantId());
                    success++;
                } catch (Exception e) {
                    failed.add(Map.of("row", rowNum, "error", e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            }
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "解析失败: " + e.getMessage());
        }
        return Map.of(
                "code", 0, "message", "imported",
                "data", Map.of(
                        "total", total,
                        "success", success,
                        "failed", failed.size(),
                        "errors", failed
                )
        );
    }

    private static String escapeCsv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // 简易 CSV 行解析(支持双引号转义)
    private static String[] parseCsvLine(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"'); i++;
                    } else inQuotes = false;
                } else cur.append(c);
            } else {
                if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
                else if (c == '"') inQuotes = true;
                else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    // ============================================================
    //  DTO
    // ============================================================

    private Map<String, Object> toDto(CollectionMetaEntity meta) {
        Map<String, Object> dto = new java.util.HashMap<>();
        dto.put("id", meta.getId().toString());
        dto.put("name", meta.getName());
        dto.put("title", meta.getTitle() != null ? meta.getTitle() : meta.getName());
        dto.put("description", meta.getDescription() != null ? meta.getDescription() : "");
        dto.put("fields_json", meta.getFieldsJson());
        dto.put("tenant_id", meta.getTenantId());
        dto.put("created_at", meta.getCreatedAt().toString());
        return dto;
    }

    public record CreateCollectionRequest(
            @NotBlank
            @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$", message = "name 必须是小写字母开头")
            String name,
            String title,
            String description,
            List<FieldDef> fields
    ) {
        public CreateCollectionRequest {
            if (fields == null) fields = List.of();
        }
    }

    public record UpdateCollectionRequest(
            String title,
            String description
    ) {}

    public record RenameFieldRequest(
            @NotBlank
            @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$")
            String newName
    ) {}
}