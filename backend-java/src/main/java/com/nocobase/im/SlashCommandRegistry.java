package com.nocobase.im;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Slash 命令 SPI 注册表。
 *
 * <p>对齐现有 workflow/handler 注册风格：通过 SPI 发现命令实现，
 * 默认内置 5 个命令（/remind /poll /code /invite /ai）。
 */
public class SlashCommandRegistry {

    private final Map<String, BiConsumer<String, Map<String, Object>>> commands = new ConcurrentHashMap<>();
    /** 命令描述（与 commands 同步维护，供 GET /api/im/slash/commands 暴露）。 */
    private final Map<String, String> descriptions = new ConcurrentHashMap<>();

    public void register(String command, BiConsumer<String, Map<String, Object>> handler) {
        commands.put(command.toLowerCase(), handler);
    }

    public void register(String command, String description,
                         BiConsumer<String, Map<String, Object>> handler) {
        String key = command.toLowerCase();
        commands.put(key, handler);
        descriptions.put(key, description);
    }

    public boolean has(String command) {
        return commands.containsKey(command.toLowerCase());
    }

    public BiConsumer<String, Map<String, Object>> get(String command) {
        return commands.get(command.toLowerCase());
    }

    public void clear() {
        commands.clear();
        descriptions.clear();
    }

    /** 已注册命令列表（名称 + 描述），供前端 Slash 面板展示。 */
    public List<Map<String, String>> listCommands() {
        return commands.keySet().stream()
                .sorted()
                .map(k -> Map.of(
                        "name", "/" + k,
                        "description", descriptions.getOrDefault(k, "")))
                .toList();
    }

    /** 初始化默认 5 个内置命令（handler 体由 {@link SlashCommandInitializer} 启动后注入）。 */
    public static SlashCommandRegistry defaultRegistry() {
        SlashCommandRegistry reg = new SlashCommandRegistry();
        reg.register("remind", "设置定时提醒（/remind 内容）", (content, ctx) -> { /* 占位 */ });
        reg.register("poll", "发起投票（/poll 问题）", (content, ctx) -> { /* 占位 */ });
        reg.register("code", "插入代码块（/code 语言）", (content, ctx) -> { /* 占位 */ });
        reg.register("invite", "邀请成员加入频道（/invite 用户）", (content, ctx) -> { /* 占位 */ });
        reg.register("ai", "触发 AI Agent 处理（/ai 提示词）", (content, ctx) -> { /* 占位 */ });
        return reg;
    }
}