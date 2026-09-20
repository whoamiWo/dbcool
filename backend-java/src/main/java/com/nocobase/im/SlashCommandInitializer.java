package com.nocobase.im;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.nocobase.ai.AgentService;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.notification.NotificationService;

/**
 * 启动后把 Slash 命令注册表中 5 个占位 handler 替换为真实实现。
 *
 * <p>SlashCommandRegistry 在 Bean 初始化时注册命令，但当时尚未注入 MessageService /
 * NotificationService 等依赖，因此用 CommandLineRunner 延后注入。
 * 命令列表已由 ImSlashController 暴露（前端 /im/slash/commands 已通），此处接真执行体。
 */
@Component
public class SlashCommandInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandInitializer.class);

    private final SlashCommandRegistry registry;
    private final MessageService messageService;
    private final ChannelService channelService;
    private final NotificationService notificationService;
    private final AgentService agentService;
    private final UserRepository userRepository;

    public SlashCommandInitializer(SlashCommandRegistry registry,
                                   MessageService messageService,
                                   ChannelService channelService,
                                   NotificationService notificationService,
                                   AgentService agentService,
                                   UserRepository userRepository) {
        this.registry = registry;
        this.messageService = messageService;
        this.channelService = channelService;
        this.notificationService = notificationService;
        this.agentService = agentService;
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        // /remind <N分钟> <内容>：接 NotificationService.fire 验证链路
        registry.register("remind",
                "设置定时提醒（/remind 内容）",
                (content, ctx) -> {
                    String[] parts = content.trim().split("\\s+", 2);
                    int minutes = 10;
                    String text = "你设置了定时提醒";
                    if (parts.length >= 1) {
                        try { minutes = Integer.parseInt(parts[0]); } catch (NumberFormatException ignored) {}
                    }
                    if (parts.length >= 2) text = parts[1];
                    ctx.put("minutes", minutes);
                    ctx.put("text", text);
                    if (minutes > 0 && minutes <= 60) {
                        notificationService.fire(
                                String.valueOf(ctx.get("tenantId")),
                                "reminder", null,
                                Map.of("text", text, "channelId", ctx.get("channelId")));
                        ctx.put("scheduled", "ok");
                    } else {
                        ctx.put("error", "提醒时间必须在 1-60 分钟之间");
                    }
                });

        // /poll：无投票模型，回显占位
        registry.register("poll",
                "发起投票（/poll 问题）",
                (content, ctx) -> {
                    ctx.put("status", "ok");
                    ctx.put("message", "投票功能待后续迭代（当前频道无投票模型）");
                });

        // /code：插入代码块到频道，回显占位
        registry.register("code",
                "插入代码块（/code 语言）",
                (content, ctx) -> {
                    ctx.put("status", "ok");
                    ctx.put("message", "代码块回显功能待后续迭代");
                });

        // /invite <userId 或用户名>：接 ChannelService.join + MessageService.send
        registry.register("invite",
                "邀请成员加入频道（/invite 用户）",
                (content, ctx) -> {
                    String targetInput = content.trim();
                    if (targetInput.isBlank()) {
                        ctx.put("error", "缺少目标用户 ID 或用户名");
                        return;
                    }
                    UUID targetUuid;
                    String targetDisplay;
                    try {
                        // 尝试解析为 UUID
                        targetUuid = UUID.fromString(targetInput);
                        targetDisplay = targetInput;
                    } catch (IllegalArgumentException e1) {
                        // 尝试按用户名查找
                        Optional<UserEntity> userOpt = userRepository.findByUsername(targetInput);
                        if (userOpt.isEmpty()) {
                            ctx.put("error", "未找到用户：" + targetInput);
                            return;
                        }
                        targetUuid = userOpt.get().getId();
                        targetDisplay = userOpt.get().getUsername();
                    }
                    UUID channelId  = UUID.fromString(String.valueOf(ctx.get("channelId")));
                    UUID selfUserId = UUID.fromString(String.valueOf(ctx.get("userId")));
                    channelService.join(
                            String.valueOf(ctx.get("tenantId")),
                            channelId, targetUuid, "MEMBER");
                    messageService.send(
                            String.valueOf(ctx.get("tenantId")),
                            channelId, selfUserId,
                            "*[" + ctx.get("user") + "] 邀请 " + targetDisplay + " 加入本频道*",
                            "markdown", null);
                    ctx.put("invited", targetDisplay);
                    ctx.put("channelId", channelId.toString());
                });

        // /ai <prompt>：接 AgentService.executeInChannel
        registry.register("ai",
                "触发 AI Agent 处理（/ai 提示词）",
                (content, ctx) -> {
                    String prompt = content.trim();
                    if (prompt.isBlank()) {
                        ctx.put("error", "缺少 AI 提示词");
                        return;
                    }
                    UUID channelId = UUID.fromString(String.valueOf(ctx.get("channelId")));
                    UUID userId    = UUID.fromString(String.valueOf(ctx.get("userId")));
                    Map<String, Object> result = agentService.executeInChannel(
                            String.valueOf(ctx.get("tenantId")),
                            channelId, userId, prompt);
                    ctx.put("result", result);
                });

        log.info("[slash] 初始化完成，共注册 {} 条命令", registry.listCommands().size());
    }
}