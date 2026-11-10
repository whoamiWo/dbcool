package com.nocobase.auth.oidc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public OIDC one-time-code exchange; authentication is the code itself. */
@RestController
@RequestMapping("/api/auth/oidc")
public class OidcAuthController {

    private final OidcLoginCodeService loginCodeService;
    private final com.nocobase.audit.AuditService auditService;

    public OidcAuthController(OidcLoginCodeService loginCodeService,
                              com.nocobase.audit.AuditService auditService) {
        this.loginCodeService = loginCodeService;
        this.auditService = auditService;
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/exchange")
    public Map<String, Object> exchange(@RequestBody ExchangeRequest request) {
        try {
            OidcLoginCodeService.ExchangeResult result = loginCodeService.exchange(request.code());
            auditService.log(result.user().getTenantId(), result.user().getId().toString(),
                    result.user().getUsername(), "auth.oidc.exchange", "auth",
                    result.user().getId().toString(), Map.of("result", "success"));
            return response(result);
        } catch (RuntimeException e) {
            auditService.log("unknown", "anonymous", "anonymous", "auth.oidc.exchange.failed",
                    "auth", "unknown", Map.of("result", "rejected"));
            throw e;
        }
    }

    private Map<String, Object> response(OidcLoginCodeService.ExchangeResult result) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", result.user().getId().toString());
        user.put("username", result.user().getUsername());
        user.put("display_name", result.user().getDisplayName() != null
                ? result.user().getDisplayName() : result.user().getUsername());
        user.put("tenant_id", result.user().getTenantId());
        user.put("roles", result.roles());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("access_token", result.accessToken());
        data.put("accessToken", result.accessToken());
        data.put("token_type", "Bearer");
        data.put("expires_in", result.expiresIn());
        data.put("user", user);
        data.put("issued_at", Instant.now().toString());
        return Map.of("code", 0, "message", "success", "data", data);
    }

    public record ExchangeRequest(String code) {}
}
