package com.nocobase.approval;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批中心 API（E2）。
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Approvals", description = "审批单据与审批中心")
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/templates")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> templates(@AuthenticationPrincipal AuthenticatedUser user) {
        List<Map<String, Object>> data = approvalService.templates(user.tenantId()).stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId().toString(),
                        "name", t.getName(),
                        "icon", t.getIcon() == null ? "" : t.getIcon(),
                        "category", t.getCategory() == null ? "" : t.getCategory(),
                        "formId", t.getFormId() == null ? "" : t.getFormId(),
                        "approverType", t.getApproverType()))
                .toList();
        return Map.of("code", 0, "message", "success", "data", data);
    }

    @PostMapping("/templates/from-preset")
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN) or hasAuthority('workflow:write')")
    public Map<String, Object> fromPreset(@RequestBody FromPresetRequest req,
                                          @AuthenticationPrincipal AuthenticatedUser user) {
        ApprovalTemplateEntity t = approvalService.createFromPreset(
                user.tenantId(), user.userId(), req.name(), req.category(),
                req.levels() == null ? 1 : req.levels(), req.approverType(), req.assignee());
        return Map.of("code", 0, "message", "success",
                "data", Map.of("id", t.getId().toString(), "workflowId", t.getWorkflowId().toString()));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> submit(@RequestBody SubmitRequest req,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        Map<String, Object> data = approvalService.submit(
                user.tenantId(), user.userId(), req.templateId(), req.formData(), req.ccUserIds());
        return Map.of("code", 0, "message", "success", "data", data);
    }

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("code", 0, "message", "success",
                "data", approvalService.listMine(user.tenantId(), user.userId()));
    }

    @GetMapping("/todo")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> todo(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("code", 0, "message", "success",
                "data", approvalService.listTodo(user.tenantId(), user.userId()));
    }

    @GetMapping("/cc")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> cc(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("code", 0, "message", "success",
                "data", approvalService.listCc(user.tenantId(), user.userId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> detail(@PathVariable UUID id,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("code", 0, "message", "success",
                "data", approvalService.detail(user.tenantId(), id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> cancel(@PathVariable UUID id,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("code", 0, "message", "success",
                "data", approvalService.cancel(user.tenantId(), id, user.userId()));
    }

    public record SubmitRequest(UUID templateId, Map<String, Object> formData,
                                List<UUID> ccUserIds) {}

    public record FromPresetRequest(String name, String category, Integer levels,
                                    String approverType, String assignee) {}
}
