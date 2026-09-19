package com.nocobase.im;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
