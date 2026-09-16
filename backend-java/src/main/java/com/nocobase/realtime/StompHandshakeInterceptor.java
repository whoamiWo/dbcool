package com.nocobase.realtime;

import com.nocobase.auth.JwtService;
import com.nocobase.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * STOMP 握手鉴权。
 *
 * <p>浏览器 WebSocket API 无法自定义请求头,因此 token 走查询参数:
 * {@code /ws/im?token=<access_token>}。
 *
 * <p>校验通过后:
 * <ol>
 *   <li>把 {@link StompPrincipal} 写入 attributes,供订阅拦截器做租户校验</li>
 *   <li>同步 {@code TenantContext.set(...)} —— WS 线程不经过 {@code JwtAuthFilter},
 *       若不绑定,下游 service 取到的会是默认租户
 *       (这正是既有 API Key 过滤器踩过的同类坑)</li>
 * </ol>
 */
public class StompHandshakeInterceptor implements HandshakeInterceptor {

    private static final String TOKEN_PARAM = "token";

    private static final Logger log = LoggerFactory.getLogger(StompHandshakeInterceptor.class);

    private final JwtService jwtService;

    public StompHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            log.warn("[ws] 握手拒绝:缺少 token 参数");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Claims claims = jwtService.parseAccessToken(token);
        if (claims == null) {
            log.warn("[ws] 握手拒绝:token 无效或已过期");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            UUID userId = UUID.fromString(claims.getSubject());
            String username = claims.get("username", String.class);
            String tenantId = claims.get("tid", String.class);
            if (tenantId == null || tenantId.isBlank()) {
                // 老 token 无 tid 声明:回落默认租户,与 JwtAuthFilter 行为保持一致
                tenantId = TenantContext.DEFAULT_TENANT;
            }
            attributes.put(StompPrincipal.KEY, new StompPrincipal(userId, username, tenantId));
            TenantContext.set(tenantId);
            return true;
        } catch (Exception e) {
            log.warn("[ws] 握手拒绝:token 解析失败 {}", e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手结束即释放,避免线程复用导致租户串号(报告 C-R02)
        TenantContext.clear();
    }

    private static String extractToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servlet) {
            String p = servlet.getServletRequest().getParameter(TOKEN_PARAM);
            if (p != null && !p.isBlank()) return p;
        }
        String query = request.getURI() == null ? null : request.getURI().getQuery();
        if (query == null || query.isBlank()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && TOKEN_PARAM.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
