package com.nocobase.api;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端点 — Week 4 真接 JWT 解析.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    /**
     * 当前用户信息(从 JWT 解析).
     */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            // 理论上 SecurityConfig 已拦截,这里兜底
            return Map.of(
                    "code", 1001,
                    "message", "未认证",
                    "data", Map.of()
            );
        }
        return Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "id", user.userId().toString(),
                        "username", user.username(),
                        "tenant_id", user.tenantId(),
                        "roles", new String[]{"admin"}
                )
        );
    }
}
