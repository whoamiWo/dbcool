package com.nocobase.plugin;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 插件市场 REST API。
 */
@RestController
@Tag(name = "Plugin Marketplace", description = "插件市场")
@RequestMapping("/api/marketplace")
public class PluginMarketplaceController {

    private final PluginMarketplaceService marketplaceService;

    public PluginMarketplaceController(PluginMarketplaceService marketplaceService) {
        this.marketplaceService = marketplaceService;
    }

    /** 浏览应用列表 */
    @GetMapping("/apps")
    public ResponseEntity<Map<String, Object>> listApps(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        List<Map<String, Object>> apps = marketplaceService.listApps(user.tenantId(), category, limit);
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("apps", apps)
        ));
    }

    /** 获取应用详情 */
    @GetMapping("/apps/{appKey}")
    public ResponseEntity<Map<String, Object>> getApp(
            @PathVariable String appKey,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        Map<String, Object> app = marketplaceService.getApp(appKey, user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", app
        ));
    }

    /** 安装应用 */
    @PostMapping("/apps/{appKey}/install")
    public ResponseEntity<Map<String, Object>> installApp(
            @PathVariable String appKey,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) body.get("config");
        
        Map<String, Object> app = marketplaceService.installApp(
                appKey, user.tenantId(), user.userId(), config);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0,
                "message", "success",
                "data", app
        ));
    }

    /** 卸载应用 */
    @PostMapping("/apps/{appKey}/uninstall")
    public ResponseEntity<Map<String, Object>> uninstallApp(
            @PathVariable String appKey,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        marketplaceService.uninstallApp(appKey, user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success"
        ));
    }

    /** 更新应用配置 */
    @PutMapping("/apps/{appKey}/config")
    public ResponseEntity<Map<String, Object>> updateConfig(
            @PathVariable String appKey,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) body.get("config");
        
        Map<String, Object> app = marketplaceService.updateConfig(
                appKey, user.tenantId(), config);
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", app
        ));
    }

    /** 检查更新 */
    @GetMapping("/apps/{appKey}/update-check")
    public ResponseEntity<Map<String, Object>> checkUpdate(
            @PathVariable String appKey,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        Map<String, Object> result = marketplaceService.checkUpdate(appKey, user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", result
        ));
    }

    /** 列出租户已安装应用 */
    @GetMapping("/installed")
    public ResponseEntity<Map<String, Object>> listInstalled(
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        List<Map<String, Object>> apps = marketplaceService.listInstalledApps(user.tenantId());
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("apps", apps)
        ));
    }
}
