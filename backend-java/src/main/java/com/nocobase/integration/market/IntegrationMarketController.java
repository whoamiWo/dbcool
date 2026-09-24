package com.nocobase.integration.market;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 集成市场 REST API — P0-5a 接真。
 *
 * <p>三个端点:
 * <ul>
 *   <li>{@code GET  /api/integrations/market}  — 列出所有可用集成(内置 + 插件)</li>
 *   <li>{@code POST /api/integrations/install/{id}}    — 安装(校验 + 回显)</li>
 *   <li>{@code POST /api/integrations/uninstall/{id}}  — 卸载(引导去通知渠道或插件目录)</li>
 * </ul>
 *
 * <p>前端 {@code src/api/integrations.ts:integrationApi} 调用此三端点。
 */
@RestController
@Tag(name = "Integration Market", description = "集成市场")
@RequestMapping("/api/integrations")
public class IntegrationMarketController {

    private final IntegrationMarketService marketService;

    public IntegrationMarketController(IntegrationMarketService marketService) {
        this.marketService = marketService;
    }

    /** 列出所有可用集成。 */
    @GetMapping("/market")
    public ResponseEntity<Map<String, Object>> listMarket(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<Map<String, Object>> apps = marketService.listApps(user == null ? null : user.tenantId());
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("integrations", apps)
        ));
    }

    /** 安装集成。 */
    @PostMapping("/install/{id}")
    public ResponseEntity<Map<String, Object>> install(
            @PathVariable String id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Map<String, Object> result = marketService.installApp(id, user == null ? null : user.tenantId());
        int code = (int) result.getOrDefault("code", 0);
        return ResponseEntity.status(code >= 400 ? code : 200).body(result);
    }

    /** 卸载集成。 */
    @PostMapping("/uninstall/{id}")
    public ResponseEntity<Map<String, Object>> uninstall(
            @PathVariable String id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Map<String, Object> result = marketService.uninstallApp(id, user == null ? null : user.tenantId());
        int code = (int) result.getOrDefault("code", 0);
        return ResponseEntity.status(code >= 400 ? code : 200).body(result);
    }
}