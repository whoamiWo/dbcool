package com.nocobase.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.GenericMessage;

/** PHASE105: TenantSubscriptionInterceptor 测试。 */
class TenantSubscriptionInterceptorTest {

    private final TenantSubscriptionInterceptor interceptor = new TenantSubscriptionInterceptor();

    @Test
    void preSend_notSubscribe_returnsMessage() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<?> msg = new GenericMessage<>("test", accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, mock(MessageChannel.class));

        assertThat(result).isSameAs(msg);
    }

    @Test
    void preSend_missingTenantInDest_returnsNull() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/doc1"); // 无租户段
        Message<?> msg = new GenericMessage<>("test", accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    @Test
    void preSend_unauthenticated_returnsNull() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/t1.collab.doc1");
        // 无 principal
        Message<?> msg = new GenericMessage<>("test", accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    @Test
    void preSend_tenantMismatch_returnsNull() {
        UUID userId = UUID.randomUUID();
        StompPrincipal principal = new StompPrincipal(userId, "u1", "t1");

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/t2.collab.doc1"); // t2
        accessor.setUser(principal); // 但用户属于 t1

        Message<?> msg = new GenericMessage<>("test", accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    @Test
    void preSend_tenantMatch_returnsMessage() {
        UUID userId = UUID.randomUUID();
        StompPrincipal principal = new StompPrincipal(userId, "u1", "t1");

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/t1.collab.doc1");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(StompPrincipal.KEY, principal);
        accessor.setSessionAttributes(attrs);

        // 使用 accessor.buildMessage() 而不是 GenericMessage，确保 headers 包含 session attributes
        Message<?> msg = org.springframework.messaging.support.MessageBuilder
                .withPayload("test")
                .copyHeaders(accessor.getMessageHeaders())
                .build();

        Message<?> result = interceptor.preSend(msg, mock(MessageChannel.class));

        assertThat(result).isNotNull();
    }

}
