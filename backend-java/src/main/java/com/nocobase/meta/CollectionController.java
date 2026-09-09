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
@RequestMapping("/api/collections")
public class CollectionController {

    private final CollectionService service;
    private final AsyncMigrationService migrationService;
    private final MigrationJobRepository jobRepository;

    public CollectionController(
            CollectionService service,
            AsyncMigrationService migrationService,
            MigrationJobRepository jobRepository
    ) {
        this.service = service;
        this.migrationService = migrationService;
        this.jobRepository = jobRepository;
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
        UUID id = service.insertRecord(name, data, user.tenantId());
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
        return Map.of(
                "code", 0, "message", "success",
                "data", service.listRecords(name, user.tenantId(), limit)
        );
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