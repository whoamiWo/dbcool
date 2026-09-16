package com.nocobase.realtime;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * 订阅租户校验 —— 防止跨租户订阅。
 *
 * <p>destination 完全由客户端构造,若不做校验,A 租户的客户端
 * 只要猜到 B 租户的频道 id 就能订阅到对方消息。这里强制校验
 * destination 中的租户段必须与握手身份的 tenantId 一致。
 *
 * <p>返回 {@code null} 表示中断该消息(Spring 语义:不继续投递)。
 */
public class TenantSubscriptionInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantSubscriptionInterceptor.class);

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        String destTenant = StompDestinations.parseTenantId(destination);
        if (destTenant == null) {
            log.warn("[ws] 拒绝订阅: destination 缺少租户段 -> {}", destination);
            return null;
        }

        StompPrincipal principal = resolvePrincipal(accessor);
        if (principal == null) {
            log.warn("[ws] 拒绝订阅: 会话未认证");
            return null;
        }
        if (!destTenant.equals(principal.tenantId())) {
            log.warn("[ws] 拒绝跨租户订阅: user={} tenant={} destTenant={}",
                    principal.userId(), principal.tenantId(), destTenant);
            return null;
        }
        return message;
    }

    private static StompPrincipal resolvePrincipal(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof StompPrincipal p) return p;
        Map<String, Object> attrs = accessor.getSessionAttributes();
        Object attr = attrs == null ? null : attrs.get(StompPrincipal.KEY);
        return attr instanceof StompPrincipal p ? p : null;
    }
}
