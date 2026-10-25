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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Form REST API — Epic 2 表单设计器.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Forms", description = "表单")
@RequestMapping("/api/forms")
public class FormController {

    private final FormService service;
    /** PHASE10: share/unshare 审计留痕。 */
    private final com.nocobase.audit.AuditService auditService;

    public FormController(FormService service, com.nocobase.audit.AuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody @Valid CreateFormRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FormEntity form = service.create(
                request.collectionName(), request.title(), request.description(),
                request.layout(), request.rules(),
                user.tenantId(), user.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0, "message", "success", "data", toDto(form)
        ));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String collection,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<FormEntity> forms = (collection != null && !collection.isBlank())
                ? service.listByCollection(collection, user.tenantId())
                : service.listAll(user.tenantId());
        return Map.of(
                "code", 0, "message", "success",
                "data", forms.stream().map(this::toDto).toList()
        );
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public Map<String, Object> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FormEntity form = service.get(id, user.tenantId());
        Map<String, Object> dto = toDto(form);
        dto.put("layout", service.parseLayout(form));
        dto.put("rules", service.parseRules(form));
        return Map.of("code", 0, "message", "success", "data", dto);
    }

    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable UUID id,
            @RequestBody UpdateFormRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FormEntity form = service.update(
                id, request.title(), request.description(),
                request.layout(), request.rules(), user.tenantId());
        return Map.of("code", 0, "message", "success", "data", toDto(form));
    }

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        service.delete(id, user.tenantId());
        return Map.of("code", 0, "message", "deleted", "data", Map.of());
    }

    // ============================================================
    //  PHASE10: 公开表单分享（免登录填写）
    // ============================================================

    /** 开启/重新生成公开分享链接（返回 share_token）。 */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/share")
    public Map<String, Object> share(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean regenerate,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FormEntity form = service.share(id, user.tenantId(), regenerate);
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "form.share", "form", id.toString(),
                Map.of("regenerate", regenerate));
        return Map.of("code", 0, "message", "success",
                "data", Map.of("share_token", String.valueOf(form.getShareToken())));
    }

    /** 撤销公开分享（原链接立即失效）。 */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{id}/share")
    public Map<String, Object> unshare(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        service.unshare(id, user.tenantId());
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "form.unshare", "form", id.toString(), Map.of());
        return Map.of("code", 0, "message", "success", "data", Map.of());
    }

    /** 免登录：按 token 取表单定义（无效 → 404）。 */
    @GetMapping("/public/{token}")
    public Map<String, Object> getPublic(@PathVariable String token) {
        FormEntity form = service.getByShareToken(token);
        Map<String, Object> dto = new java.util.HashMap<>();
        dto.put("id", form.getId().toString());
        dto.put("title", form.getTitle());
        dto.put("description", form.getDescription() != null ? form.getDescription() : "");
        dto.put("layout", service.parseLayout(form));
        dto.put("rules", service.parseRules(form));
        return Map.of("code", 0, "message", "success", "data", dto);
    }

    /** 免登录：匿名提交，数据落表单绑定的 collection。 */
    @PostMapping("/public/{token}/submit")
    public ResponseEntity<Map<String, Object>> submitPublic(
            @PathVariable String token,
            @RequestBody Map<String, Object> body
    ) {
        UUID recordId = service.submitPublic(token, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0, "message", "success", "data", Map.of("id", recordId.toString())
        ));
    }

    private Map<String, Object> toDto(FormEntity form) {
        Map<String, Object> dto = new java.util.HashMap<>();
        dto.put("id", form.getId().toString());
        dto.put("collection_name", form.getCollectionName());
        dto.put("title", form.getTitle());
        dto.put("description", form.getDescription() != null ? form.getDescription() : "");
        dto.put("layout_json", form.getLayoutJson());
        dto.put("rules_json", form.getRulesJson());
        dto.put("tenant_id", form.getTenantId());
        dto.put("created_at", form.getCreatedAt().toString());
        dto.put("updated_at", form.getUpdatedAt() != null ? form.getUpdatedAt().toString() : null);
        dto.put("share_token", form.getShareToken());
        return dto;
    }

    public record CreateFormRequest(
            @NotBlank String collectionName,
            @NotBlank String title,
            String description,
            String layout,
            String rules
    ) {}

    public record UpdateFormRequest(
            String title,
            String description,
            String layout,
            String rules
    ) {}
}
