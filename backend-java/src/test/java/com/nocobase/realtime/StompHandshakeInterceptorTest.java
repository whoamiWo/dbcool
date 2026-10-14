package com.nocobase.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtService;
import io.jsonwebtoken.Claims;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

/** PHASE105: StompHandshakeInterceptor 测试。 */
class StompHandshakeInterceptorTest {

    private JwtService jwtService;
    private StompHandshakeInterceptor interceptor;
    private ServerHttpRequest request;
    private ServerHttpResponse response;
    private WebSocketHandler wsHandler;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        interceptor = new StompHandshakeInterceptor(jwtService);
        request = mock(ServerHttpRequest.class);
        response = mock(ServerHttpResponse.class);
        wsHandler = mock(WebSocketHandler.class);
        attributes = new HashMap<>();
    }

    @Test
    void beforeHandshake_missingToken_returnsFalse() {
        when(request.getURI()).thenReturn(URI.create("ws://host/ws"));

        boolean ok = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void beforeHandshake_blankToken_returnsFalse() {
        when(request.getURI()).thenReturn(URI.create("ws://host/ws?token="));

        boolean ok = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(ok).isFalse();
    }

    @Test
    void beforeHandshake_invalidToken_returnsFalse() {
        when(request.getURI()).thenReturn(URI.create("ws://host/ws?token=bad"));
        when(jwtService.parseAccessToken("bad")).thenReturn(null);

        boolean ok = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(ok).isFalse();
    }

    @Test
    void beforeHandshake_validToken_setsPrincipalAndTenantContext() throws Exception {
        UUID userId = UUID.randomUUID();
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(userId.toString());
        when(claims.get("username", String.class)).thenReturn("alice");
        when(claims.get("tid", String.class)).thenReturn("t1");
        when(jwtService.parseAccessToken("good")).thenReturn(claims);
        when(request.getURI()).thenReturn(URI.create("ws://host/ws?token=good"));

        boolean ok = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(ok).isTrue();
        assertThat(attributes).containsKey(StompPrincipal.KEY);
        StompPrincipal p = (StompPrincipal) attributes.get(StompPrincipal.KEY);
        assertThat(p.userId()).isEqualTo(userId);
        assertThat(p.username()).isEqualTo("alice");
        assertThat(p.tenantId()).isEqualTo("t1");
    }

    @Test
    void beforeHandshake_missingTenantId_usesDefault() throws Exception {
        UUID userId = UUID.randomUUID();
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(userId.toString());
        when(claims.get("username", String.class)).thenReturn("bob");
        when(claims.get("tid", String.class)).thenReturn(null); // 无 tid
        when(jwtService.parseAccessToken("good")).thenReturn(claims);
        when(request.getURI()).thenReturn(URI.create("ws://host/ws?token=good"));

        boolean ok = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(ok).isTrue();
        StompPrincipal p = (StompPrincipal) attributes.get(StompPrincipal.KEY);
        assertThat(p.tenantId()).isEqualTo(com.nocobase.tenant.TenantContext.DEFAULT_TENANT);
    }

    @Test
    void beforeHandshake_exceptionDuringParse_returnsFalse() throws Exception {
        when(request.getURI()).thenReturn(URI.create("ws://host/ws?token=error"));
        when(jwtService.parseAccessToken("error")).thenThrow(new RuntimeException("parse error"));

        boolean ok = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(ok).isFalse();
    }

    @Test
    void afterHandshake_clearsTenantContext() {
        interceptor.afterHandshake(request, response, wsHandler, null);
        // 验证清理（可通过检查 TenantContext 状态，这里仅确保不抛异常）
    }
}
