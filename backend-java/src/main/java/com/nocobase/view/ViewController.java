package com.nocobase.view;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.HashMap;
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

/**
 * 视图 REST API — Week 9 Epic 3.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Views", description = "视图")
@RequestMapping("/api/views")
public class ViewController {

    private final ViewService service;

    public ViewController(ViewService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody @Valid CreateViewRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        ViewEntity.Type type;
        try {
            type = ViewEntity.Type.valueOf(request.type().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "type 必须是 table / kanban / detail / gallery / calendar / timeline");
        }

        ViewEntity view = service.create(
                request.collectionName(),
                request.name(),
                request.title(),
                type,
                request.config(),
                request.sharedWith(),
                user.tenantId(),
                user.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0, "message", "success", "data", toDto(view)
        ));
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String collection,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<ViewEntity> views = (collection != null && !collection.isBlank())
                ? service.listByCollection(collection, user.tenantId())
                : service.listAll(user.tenantId());
        return Map.of(
                "code", 0, "message", "success",
                "data", views.stream().map(this::toDto).toList()
        );
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        ViewEntity view = service.get(id, user.tenantId());
        Map<String, Object> dto = toDto(view);
        dto.put("config", service.parseConfig(view));
        return Map.of("code", 0, "message", "success", "data", dto);
    }

    @GetMapping("/{id}/timeline-records")
    public Map<String, Object> timelineRecords(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String filterStart,
            @RequestParam(required = false) String filterEnd
    ) {
        ViewEntity view = service.get(id, user.tenantId());
        if (view.getType() != ViewEntity.Type.TIMELINE) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "视图类型不是 TIMELINE");
        }
        var records = service.listTimelineRecords(view, limit, filterStart, filterEnd);
        return Map.of("code", 0, "message", "success", "data", records);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable UUID id,
            @RequestBody UpdateViewRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        ViewEntity view = service.update(
                id, request.name(), request.title(),
                request.config(), request.sharedWith(),
                user.tenantId());
        return Map.of("code", 0, "message", "success", "data", toDto(view));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        service.delete(id, user.tenantId());
        return Map.of("code", 0, "message", "deleted", "data", Map.of());
    }

    private Map<String, Object> toDto(ViewEntity view) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", view.getId().toString());
        dto.put("collection_name", view.getCollectionName());
        dto.put("name", view.getName());
        dto.put("title", view.getTitle() != null ? view.getTitle() : view.getName());
        dto.put("type", view.getType().name());
        dto.put("config_json", view.getConfigJson());
        dto.put("shared_with_json", view.getSharedWithJson());
        dto.put("tenant_id", view.getTenantId());
        dto.put("created_at", view.getCreatedAt().toString());
        dto.put("updated_at", view.getUpdatedAt() != null ? view.getUpdatedAt().toString() : null);
        dto.put("created_by", view.getCreatedBy() != null ? view.getCreatedBy().toString() : null);
        return dto;
    }

    public record CreateViewRequest(
            @NotBlank String collectionName,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$") String name,
            String title,
            @NotBlank String type,
            String config,
            String sharedWith
    ) {}

    public record UpdateViewRequest(
            String name,
            String title,
            String config,
            String sharedWith
    ) {}
}
