package com.nocobase.api;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.RoleEntity;
import com.nocobase.auth.UserRoleRepository;
import com.nocobase.auth.RoleRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端点 — Week 4 真接 JWT 解析.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Users", description = "当前用户")
@RequestMapping("/api/users")
public class UserController {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;

    public UserController(UserRoleRepository userRoleRepository, RoleRepository roleRepository) {
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
    }

    /**
     * 当前用户信息(从 JWT 解析),含真实角色列表.
     */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            return Map.of(
                    "code", 1001,
                    "message", "未认证",
                    "data", Map.of()
            );
        }
        List<String> roleNames = userRoleRepository.findByIdUserId(user.userId()).stream()
                .map(ur -> roleRepository.findById(ur.getId().getRoleId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(RoleEntity::getName)
                .toList();
        return Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "id", user.userId().toString(),
                        "username", user.username(),
                        "tenant_id", user.tenantId(),
                        "roles", roleNames
                )
        );
    }
}
