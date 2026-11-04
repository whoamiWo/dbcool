package com.nocobase.org;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * 组织架构与通讯录 API（E1）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>读接口（树/成员）对登录用户开放 —— 通讯录是全员可见的基础能力。</li>
 *   <li>写接口仅 ADMIN，且仅 {@code source='LOCAL'} 的子树可写；外部来源子树
 *       由 {@link OrgUnitService} 直接返回 403，不依赖前端置灰。</li>
 *   <li>字段级权限（决策 D7）：姓名/部门/岗位/上级/邮箱全员可见；
 *       <b>手机号仅 ADMIN/HR</b> —— 在接口层不返回，而非前端隐藏。</li>
 * </ul>
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Org", description = "组织架构与通讯录")
@RequestMapping("/api/org")
public class OrgUnitController {

    /** D7 预留：当前只有 ADMIN 可看手机号；新增 HR 角色时在此扩展。 */
    private static final java.util.Set<String> PHONE_VISIBLE_ROLES = java.util.Set.of("ADMIN");

    private final OrgUnitService orgUnitService;
    private final UserRepository userRepository;

    public OrgUnitController(OrgUnitService orgUnitService, UserRepository userRepository) {
        this.orgUnitService = orgUnitService;
        this.userRepository = userRepository;
    }

    @GetMapping("/tree")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> tree(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("code", 0, "message", "success",
                "data", orgUnitService.tree(user.tenantId()));
    }

    @GetMapping("/members")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> members(@RequestParam(required = false) UUID unitId,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "true") boolean includeDescendants,
                                       @AuthenticationPrincipal AuthenticatedUser user) {
        boolean showPhone = canSeePhone();
        List<Map<String, Object>> data = orgUnitService
                .members(user.tenantId(), unitId, keyword, includeDescendants)
                .stream()
                .map(u -> toMemberDto(u, showPhone))
                .toList();
        return Map.of("code", 0, "message", "success", "data", data);
    }

    @PostMapping("/units")
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    public Map<String, Object> create(@RequestBody CreateUnitRequest req,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        OrgUnitEntity unit = orgUnitService.createLocal(
                user.tenantId(), req.parentId(), req.name(), req.code());
        return Map.of("code", 0, "message", "success", "data", unit.getId().toString());
    }

    @PutMapping("/units/{id}")
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    public Map<String, Object> update(@PathVariable UUID id,
                                      @RequestBody UpdateUnitRequest req,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        if (req.parentId() != null) {
            orgUnitService.move(id, user.tenantId(), req.parentId());
        }
        if (req.name() != null && !req.name().isBlank()) {
            orgUnitService.rename(id, user.tenantId(), req.name());
        }
        return Map.of("code", 0, "message", "success");
    }

    @DeleteMapping("/units/{id}")
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    public Map<String, Object> delete(@PathVariable UUID id,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        orgUnitService.delete(id, user.tenantId());
        return Map.of("code", 0, "message", "deleted");
    }

    /** 管理员把用户调整到某部门（仅 LOCAL 子树）。 */
    @PutMapping("/members/{userId}/unit")
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    public Map<String, Object> moveMember(@PathVariable UUID userId,
                                          @RequestBody MoveMemberRequest req,
                                          @AuthenticationPrincipal AuthenticatedUser user) {
        orgUnitService.moveUser(userId, user.tenantId(), req.unitId());
        return Map.of("code", 0, "message", "success");
    }

    /** 单人档案（通讯录卡片）。 */
    @GetMapping("/members/{userId}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> member(@PathVariable UUID userId,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        UserEntity u = userRepository.findByIdAndTenantId(userId, user.tenantId())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "用户不存在"));
        return Map.of("code", 0, "message", "success", "data", toMemberDto(u, canSeePhone()));
    }

    // ------------------------------------------------------------------

    private boolean canSeePhone() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String role = a.getAuthority();
            if (role != null && role.startsWith("ROLE_")
                    && PHONE_VISIBLE_ROLES.contains(role.substring(5))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> toMemberDto(UserEntity u, boolean showPhone) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", u.getId().toString());
        dto.put("username", u.getUsername());
        dto.put("display_name", u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
        dto.put("email", u.getEmail());
        dto.put("position", u.getPosition());
        dto.put("employee_no", u.getEmployeeNo());
        dto.put("avatar_url", u.getAvatarUrl());
        dto.put("status", u.getStatus());
        dto.put("enabled", u.isEnabled());
        dto.put("department_id", u.getDepartmentId() == null ? null : u.getDepartmentId().toString());
        dto.put("manager_id", u.getManagerId() == null ? null : u.getManagerId().toString());
        // D7：手机号只对 ADMIN/HR 返回；其余角色连字段都不下发
        if (showPhone) {
            dto.put("phone", u.getPhone());
        }
        return dto;
    }

    public record CreateUnitRequest(UUID parentId, String name, String code) {}

    public record UpdateUnitRequest(UUID parentId, String name) {}

    public record MoveMemberRequest(UUID unitId) {}
}
