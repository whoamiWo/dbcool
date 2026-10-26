package com.nocobase.integration.common;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一 JS-SDK 签名接口（钉钉 / 企业微信 / 飞书工作台嵌入）。
 *
 * <p>前端在钉钉/企微/飞书内置浏览器打开页面时，需先调用本接口拿签名，
 * 再注入 {@code dd.config} / {@code wx.config} / {@code wx.agentConfig} / 飞书 {@code h5Webview}，
 * 否则无法调用原生能力（选人、拍照、定位、分享）。
 *
 * <p>路由 {@code /api/js-signature} 符合既有约定（{@code /api/} + 小写连字符）。
 */
@RestController
@Tag(name = "JS-SDK Signature", description = "工作台嵌入 JS-SDK 签名")
@RequestMapping("/api/js-signature")
public class JsSignatureController {

    private static final Logger log = LoggerFactory.getLogger(JsSignatureController.class);

    private final Map<String, JsSignatureProvider> providers = new LinkedHashMap<>();
    private final JsTicketCache ticketCache;

    public JsSignatureController(List<JsSignatureProvider> providerList, JsTicketCache ticketCache) {
        for (JsSignatureProvider p : providerList) {
            providers.put(p.provider(), p);
        }
        this.ticketCache = ticketCache;
    }

    /**
     * 获取 JS-SDK 签名。
     *
     * @param platform 平台：dingtalk / wecom / feishu
     * @param url      当前页面 URL（不含 # 及其后部分），需与前端 location.href.split('#')[0] 一致
     * @param type     ticket 类型：企微支持 corp（默认）/ agent；钉钉、飞书忽略
     */
    @Operation(summary = "获取 JS-SDK 签名", description = "供钉钉/企微/飞书工作台内页面注入 config 使用")
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<Map<String, Object>> signature(
            @RequestParam String platform,
            @RequestParam String url,
            @RequestParam(defaultValue = "") String type,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        if (platform == null || platform.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "platform 必填");
        }
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url 必填");
        }

        JsSignatureProvider provider = providers.get(platform.toLowerCase());
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的平台：" + platform + "，可选：" + providers.keySet());
        }

        String tenantId = user == null ? null : user.tenantId();
        // 企微默认 corp ticket；其余平台 type 由实现内部决定
        String ticketType = type == null || type.isBlank()
                ? ("wecom".equals(platform.toLowerCase()) ? "corp" : "default")
                : type;

        try {
            JsSignatureProvider.JsSignature signature = provider.sign(tenantId, url, ticketType);
            return ResponseEntity.ok(Map.of(
                    "code", 0,
                    "message", "success",
                    "data", signature.toMap()
            ));
        } catch (IllegalStateException e) {
            // 凭证缺失/获取失败：明确返回，不静默降级（避免前端拿到空签名后静默失败）
            log.warn("[js-signature] 签名失败 platform={} tenant={} msg={}",
                    platform, tenantId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "code", 5003,
                    "message", e.getMessage(),
                    "data", Map.of()
            ));
        } catch (Exception e) {
            log.error("[js-signature] 签名异常 platform={} tenant={}", platform, tenantId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 5000,
                    "message", "签名生成失败：" + e.getMessage(),
                    "data", Map.of()
            ));
        }
    }

    /** 支持的平台列表（供前端下拉）。 */
    @Operation(summary = "列出支持 JS-SDK 签名的平台")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/platforms")
    public ResponseEntity<Map<String, Object>> platforms() {
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", List.copyOf(providers.keySet())
        ));
    }

    /** 清除 ticket 缓存（凭证变更后强制刷新）。 */
    @Operation(summary = "清除 jsapi_ticket 缓存")
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, Object>> evictCache(
            @RequestParam String platform,
            @RequestParam(defaultValue = "default") String type,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        JsSignatureProvider provider = providers.get(platform == null ? "" : platform.toLowerCase());
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的平台：" + platform + "，可选：" + providers.keySet());
        }
        ticketCache.evict(user == null ? null : user.tenantId(), provider.provider(), type);
        return ResponseEntity.ok(Map.of("code", 0, "message", "cleared", "data", Map.of()));
    }
}
