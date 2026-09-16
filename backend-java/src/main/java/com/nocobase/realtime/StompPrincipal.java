package com.nocobase.realtime;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP 会话身份(握手鉴权通过后写入 session attributes)。
 *
 * <p>WS 握手不走 {@code JwtAuthFilter}(它只拦截 HTTP 请求),
 * 因此握手阶段自行解析 JWT,把身份封装成 Principal 挂到会话上,
 * 供 {@link TenantSubscriptionInterceptor} 做租户校验。
 */
public record StompPrincipal(UUID userId, String username, String tenantId) implements Principal {

    /** 存放在 handshake attributes / session attributes 中的 key。 */
    public static final String KEY = "stompPrincipal";

    @Override
    public String getName() {
        return username != null && !username.isBlank() ? username : String.valueOf(userId);
    }
}
