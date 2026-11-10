package com.nocobase.integration.credential;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 最小租户凭证管理入口；请求与审计均不得记录凭证值。 */
@RestController
@RequestMapping("/api/admin/integration-credentials")
@PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
public class TenantCredentialController {

    private static final Set<String> TENANT_FIELDS = Set.of("tenant", "tenantId", "tenant_id");
    private static final Map<String, Set<String>> ALLOWED_KEYS = Map.of(
            "dingtalk", Set.of("app-key", "app-secret", "token", "encoding-aes-key", "corp-id"),
            "feishu", Set.of("app-id", "app-secret"));

    private final TenantCredentialService credentialService;
    private final AuditService auditService;

    public TenantCredentialController(TenantCredentialService credentialService,
                                      AuditService auditService) {
        this.credentialService = credentialService;
        this.auditService = auditService;
    }

    @PutMapping("/{provider}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> upsert(@PathVariable String provider,
                                      @RequestBody Map<String, Object> body,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        String normalizedProvider = provider(provider);
        if (body == null) {
            throw badRequest("请求体不能为空");
        }
        body.forEach((key, value) -> {
            if (!TENANT_FIELDS.contains(key) && !ALLOWED_KEYS.get(normalizedProvider).contains(key)) {
                throw badRequest("不支持的凭证字段: " + key);
            }
            if (ALLOWED_KEYS.get(normalizedProvider).contains(key)
                    && (!(value instanceof String text) || text.isBlank())) {
                throw badRequest("凭证值不能为空");
            }
        });
        if (body.keySet().stream().noneMatch(ALLOWED_KEYS.get(normalizedProvider)::contains)) {
            throw badRequest("至少提供一个受支持的凭证字段");
        }
        var values = new java.util.LinkedHashMap<String, String>();
        body.forEach((key, value) -> {
            if (ALLOWED_KEYS.get(normalizedProvider).contains(key)) {
                values.put(key, (String) value);
            }
        });
        credentialService.saveAll(user.tenantId(), normalizedProvider, values, user.userId().toString());
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "integration.credential.upsert", "integration_credential", normalizedProvider,
                Map.of("provider", normalizedProvider, "keys", values.keySet(), "action", "upsert"));
        return credentialService.status(user.tenantId(), normalizedProvider);
    }

    @GetMapping("/{provider}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> status(@PathVariable String provider,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        String normalizedProvider = provider(provider);
        return credentialService.status(user.tenantId(), normalizedProvider);
    }

    @PostMapping("/{provider}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> disable(@PathVariable String provider,
                                       @AuthenticationPrincipal AuthenticatedUser user) {
        String normalizedProvider = provider(provider);
        credentialService.disable(user.tenantId(), normalizedProvider, user.userId().toString());
        audit("disable", normalizedProvider, "*", user);
        return credentialService.status(user.tenantId(), normalizedProvider);
    }

    private static String provider(String provider) {
        String normalized = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        if (!ALLOWED_KEYS.containsKey(normalized)) {
            throw badRequest("不支持的集成 provider");
        }
        return normalized;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void audit(String action, String provider, String key, AuthenticatedUser user) {
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "integration.credential." + action, "integration_credential", provider,
                Map.of("provider", provider, "key", key, "action", action));
    }
}
