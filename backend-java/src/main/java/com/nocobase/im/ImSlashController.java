package com.nocobase.im;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Slash 命令 REST API（Phase 48 断链修复）。
 *
 * <p>此前前端 {@code /im/slash/commands} 无对应后端端点，属断链。
 * 此处暴露 {@link SlashCommandRegistry} 中真实注册的命令（名称 + 描述），
 * 不硬编码列表，避免与注册表漂移。
 */
@RestController
@Tag(name = "IM Slash", description = "IM — Slash 命令")
@RequestMapping("/api/im/slash")
public class ImSlashController {

    private final SlashCommandRegistry registry;

    public ImSlashController(SlashCommandRegistry registry) {
        this.registry = registry;
    }

    /** 列出可用 Slash 命令。 */
    @GetMapping("/commands")
    public Map<String, Object> commands() {
        return Map.of("code", 0, "message", "success",
                "data", Map.of("commands", registry.listCommands()));
    }

    /** 执行 Slash 命令（接真：调用真实 handler，非占位）。 */
    @PostMapping("/commands/execute")
    public ResponseEntity<Map<String, Object>> execute(@RequestBody Map<String, Object> body) {
        String command = (String) body.get("command");
        String content = (String) body.getOrDefault("content", "");
        UUID channelId = UUID.fromString(String.valueOf(body.get("channelId")));

        AuthenticatedUser user = currentUser();
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "未登录"));
        }

        java.util.Map<String, Object> ctx = new java.util.HashMap<>();
        ctx.put("tenantId", user.tenantId());
        ctx.put("userId", user.userId());
        ctx.put("user", user.username());
        ctx.put("channelId", channelId);

        registry.get(command.toLowerCase()).accept(content, ctx);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", ctx));
    }

    private AuthenticatedUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        if (auth.getPrincipal() instanceof AuthenticatedUser u) return u;
        return null;
    }
}