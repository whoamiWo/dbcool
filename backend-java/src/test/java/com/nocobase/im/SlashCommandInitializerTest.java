package com.nocobase.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;

import com.nocobase.ai.AgentService;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.notification.NotificationService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SlashCommandInitializer 测试（PHASE108 冲 85%）。
 *
 * <p>覆盖 /remind /invite /ai 三条命令的执行分支。
 */
class SlashCommandInitializerTest {

    private SlashCommandRegistry registry;
    private MessageService messageService;
    private ChannelService channelService;
    private NotificationService notificationService;
    private AgentService agentService;
    private UserRepository userRepository;
    private SlashCommandInitializer initializer;

    @BeforeEach
    void setUp() {
        registry = new SlashCommandRegistry();
        messageService = mock(MessageService.class);
        channelService = mock(ChannelService.class);
        notificationService = mock(NotificationService.class);
        agentService = mock(AgentService.class);
        userRepository = mock(UserRepository.class);

        initializer = new SlashCommandInitializer(registry, messageService, channelService,
                notificationService, agentService, userRepository);
    }

    @Test
    void run_registersRemindInviteAi() {
        initializer.run();

        assertTrue(registry.has("remind"));
        assertTrue(registry.has("invite"));
        assertTrue(registry.has("ai"));
        assertEquals(3, registry.listCommands().size());
    }

    @Test
    void remind_validMinutes_firesNotification() {
        initializer.run();
        var handler = registry.get("remind");

        Map<String, Object> ctx = new java.util.HashMap<>(Map.of(
                "tenantId", "t1",
                "channelId", UUID.randomUUID().toString(),
                "user", "alice"));

        handler.accept("5 测试提醒", ctx);

        verify(notificationService).fire(eq("t1"), eq("reminder"), any(), any());
        assertEquals("ok", ctx.get("scheduled"));
    }

    @Test
    void remind_invalidMinutes_setsError() {
        initializer.run();
        var handler = registry.get("remind");

        Map<String, Object> ctx = new java.util.HashMap<>(Map.of(
                "tenantId", "t1",
                "channelId", UUID.randomUUID().toString(),
                "user", "alice"));

        handler.accept("120 测试提醒", ctx);

        assertTrue(ctx.containsKey("error"));
    }

    @Test
    void invite_byUuid_invitesUser() {
        initializer.run();
        var handler = registry.get("invite");

        UUID channelId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        Map<String, Object> ctx = new java.util.HashMap<>(Map.of(
                "tenantId", "t1",
                "channelId", channelId.toString(),
                "user", "alice",
                "userId", userId.toString()));


        handler.accept(targetId.toString(), ctx);

        verify(channelService).join(anyString(), eq(channelId), eq(targetId), anyString());
        verify(messageService).send(anyString(), eq(channelId), eq(userId), anyString(), eq("markdown"), any());
        assertEquals(targetId.toString(), ctx.get("invited"));
    }

    @Test
    void invite_byUsername_invitesUser() {
        initializer.run();
        var handler = registry.get("invite");

        UUID channelId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String username = "bob";

        Map<String, Object> ctx = new java.util.HashMap<>(Map.of(
                "tenantId", "t1",
                "channelId", channelId.toString(),
                "user", "alice",
                "userId", userId.toString()));

        UserEntity user = new UserEntity();
        user.setId(targetId);
        user.setUsername(username);
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));

        handler.accept(username, ctx);

        verify(channelService).join(anyString(), eq(channelId), eq(targetId), anyString());
        verify(messageService).send(anyString(), eq(channelId), eq(userId), anyString(), eq("markdown"), any());
        assertEquals(username, ctx.get("invited"));
    }

    @Test
    void invite_userNotFound_setsError() {
        initializer.run();
        var handler = registry.get("invite");

        UUID channelId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Map<String, Object> ctx = new java.util.HashMap<>(Map.of(
                "tenantId", "t1",
                "channelId", channelId.toString(),
                "user", "alice",
                "userId", userId.toString()));

        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        handler.accept("unknown", ctx);

        assertTrue(ctx.containsKey("error"));
    }

    @Test
    void ai_withPrompt_executesAgent() {
        initializer.run();
        var handler = registry.get("ai");

        UUID channelId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Map<String, Object> ctx = new java.util.HashMap<>(Map.of(
                "tenantId", "t1",
                "channelId", channelId.toString(),
                "user", "alice",
                "userId", userId.toString()));

        when(agentService.executeInChannel("t1", channelId, userId, "test prompt"))
                .thenReturn(Map.of("result", "ok"));

        handler.accept("test prompt", ctx);

        verify(agentService).executeInChannel("t1", channelId, userId, "test prompt");
        assertTrue(ctx.containsKey("result"));
    }

    @Test
    void ai_emptyPrompt_setsError() {
        initializer.run();
        var handler = registry.get("ai");

        Map<String, Object> ctx = new java.util.HashMap<>(Map.of(
                "tenantId", "t1",
                "channelId", UUID.randomUUID().toString(),
                "user", "alice",
                "userId", UUID.randomUUID().toString()));

        handler.accept("", ctx);

        assertTrue(ctx.containsKey("error"));
    }
}
