package com.nocobase.im;

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

    public void register(String command, BiConsumer<String, Map<String, Object>> handler) {
        commands.put(command.toLowerCase(), handler);
    }

    public boolean has(String command) {
        return commands.containsKey(command.toLowerCase());
    }

    public BiConsumer<String, Map<String, Object>> get(String command) {
        return commands.get(command.toLowerCase());
    }

    public void clear() {
        commands.clear();
    }

    /** 初始化默认 5 个内置命令。 */
    public static SlashCommandRegistry defaultRegistry() {
        SlashCommandRegistry reg = new SlashCommandRegistry();
        reg.register("remind", (content, ctx) -> {
            // /remind 10 分钟后提醒
            String msg = content.replace("/remind", "").trim();
            // 实际实现应调用通知服务设置定时提醒
        });
        reg.register("poll", (content, ctx) -> {
            // /poll 创建投票
        });
        reg.register("code", (content, ctx) -> {
            // /code 生成代码片段
        });
        reg.register("invite", (content, ctx) -> {
            // /invite 邀请用户到频道
        });
        reg.register("ai", (content, ctx) -> {
            // /ai 触发 AI Agent
        });
        return reg;
    }
}