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

/**
 * Form REST API — Epic 2 表单设计器.
 */
@RestController
@RequestMapping("/api/forms")
public class FormController {

    private final FormService service;

    public FormController(FormService service) {
        this.service = service;
    }

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

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        service.delete(id, user.tenantId());
        return Map.of("code", 0, "message", "deleted", "data", Map.of());
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
