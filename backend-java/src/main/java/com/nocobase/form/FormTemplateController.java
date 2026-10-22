package com.nocobase.form;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

/**
 * 表单模板 REST API (US-107).
 *
 * <p>支持:
 * - 模板的 CRUD
 * - 从表单"另存为模板" (POST /api/form-templates/save-as/{formId})
 * - 从模板套用创建新表单 (POST /api/form-templates/{id}/apply)
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "FormTemplates", description = "表单模板")
@RequestMapping("/api/form-templates")
public class FormTemplateController {

    private final FormTemplateService service;
    private final com.nocobase.audit.AuditService auditService;
    private final FormService formService;
    private final com.nocobase.meta.CollectionService collectionService;

    public FormTemplateController(
            FormTemplateService service,
            com.nocobase.audit.AuditService auditService,
            FormService formService,
            com.nocobase.meta.CollectionService collectionService
    ) {
        this.service = service;
        this.auditService = auditService;
        this.formService = formService;
        this.collectionService = collectionService;
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody @Valid CreateTemplateRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FormTemplateEntity template = service.create(
                request.title(), request.description(), request.collectionName(),
                request.schemaJson(), user.tenantId(), user.userId());
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "template.create", "form_template", template.getId().toString(),
                Map.of("title", request.title()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0, "message", "success", "data", toDto(template)));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String collection,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<FormTemplateEntity> templates = (collection != null && !collection.isBlank())
                ? service.listByCollection(collection, user.tenantId())
                : service.listAll(user.tenantId());
        return Map.of("code", 0, "message", "success",
                "data", templates.stream().map(this::toDto).toList());
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public Map<String, Object> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FormTemplateEntity template = service.get(id, user.tenantId());
        Map<String, Object> dto = toDto(template);
        dto.put("schema", service.parseSchema(template));
        return Map.of("code", 0, "message", "success", "data", dto);
    }

    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateTemplateRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FormTemplateEntity template = service.update(
                id, request.title(), request.description(), request.schemaJson(), user.tenantId());
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "template.update", "form_template", id.toString(),
                Map.of("title", request.title()));
        return Map.of("code", 0, "message", "success", "data", toDto(template));
    }

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        service.delete(id, user.tenantId());
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "template.delete", "form_template", id.toString(), Map.of());
        return Map.of("code", 0, "message", "deleted", "data", Map.of());
    }

    /**
     * 从既有表单另存为模板 (US-107 便捷入口).
     *
     * <p>直接从 FormEntity 的 layout/rules JSON 字段构造 schema_json，
     * 复用 FormService.parseLayout / parseRules 解析，避免类型错位。
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/save-as/{formId}")
    public ResponseEntity<Map<String, Object>> saveAsFromForm(
            @PathVariable UUID formId,
            @RequestParam(required = false) String title,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FormEntity form = formService.get(formId, user.tenantId());
        if (title == null || title.isBlank()) {
            title = form.getTitle() + " (模板)";
        }
        // 构造 schema: 从 form 的 layout+rules + collection 信息组装
        Map<String, Object> schema = new java.util.HashMap<>();
        schema.put("collectionName", form.getCollectionName());
        schema.put("layout", formService.parseLayout(form));
        schema.put("rules", formService.parseRules(form));
        String schemaJson;
        try {
            schemaJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(schema);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "schema 序列化失败");
        }

        FormTemplateEntity template = service.create(
                title, null, form.getCollectionName(), schemaJson,
                user.tenantId(), user.userId());
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "template.save_as", "form_template", template.getId().toString(),
                Map.of("source_form", formId.toString()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0, "message", "success", "data", toDto(template)));
    }

    /**
     * 从模板套用创建新表单 (US-107 便捷入口).
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/apply")
    public ResponseEntity<Map<String, Object>> applyToNewForm(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FormTemplateEntity template = service.get(id, user.tenantId());
        Map<String, Object> schema = service.parseSchema(template);
        String collectionName = (String) schema.get("collectionName");
        if (collectionName == null || collectionName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板缺少 collectionName");
        }
        // 检查 collection 是否已存在
        boolean exists;
        try {
            collectionService.get(collectionName, user.tenantId());
            exists = true;
        } catch (org.springframework.web.server.ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                exists = false;
            } else {
                throw e;
            }
        }

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("template_id", id.toString());
        result.put("collection_name", collectionName);
        result.put("collection_exists", exists);
        result.put("layout", schema.get("layout"));
        result.put("rules", schema.get("rules"));
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", result));
    }

    private Map<String, Object> toDto(FormTemplateEntity t) {
        Map<String, Object> dto = new java.util.HashMap<>();
        dto.put("id", t.getId().toString());
        dto.put("title", t.getTitle());
        dto.put("description", t.getDescription());
        dto.put("collection_name", t.getCollectionName());
        dto.put("tenant_id", t.getTenantId());
        dto.put("created_at", t.getCreatedAt().toString());
        dto.put("updated_at", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
        return dto;
    }

    public record CreateTemplateRequest(
            @NotBlank String title,
            String description,
            String collectionName,
            String schemaJson
    ) {}

    public record UpdateTemplateRequest(
            String title,
            String description,
            String schemaJson
    ) {}
}