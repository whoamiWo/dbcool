package com.nocobase.auth;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 认证端点 — Week 4 真实版.
 *
 * <p>接入 bcrypt + JWT + Refresh Token.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Auth", description = "认证")
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    public AuthController(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
    }

    /**
     * PHASE96 T6: LdapSyncService 历史占位口令。命中该值的账号一律禁止本地登录。
     */
    private static final String LDAP_PLACEHOLDER = "LDAP_SYNCED";

    /**
     * 登录.
     */
    @PostMapping("/login")
    @Transactional
    public ResponseEntity<Map<String, Object>> login(@RequestBody @Valid LoginRequest request) {
        UserEntity user = userRepository.findByUsername(request.username())
                .orElse(null);

        if (user == null) {
            auditService.log("unknown", "anonymous", request.username(),
                    "auth.login.failed", "auth", "username_" + request.username(),
                    Map.of("username", request.username(), "reason", "user_not_found"));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }

        // PHASE96 T6: 拒绝历史占位口令的账号本地登录。
        // LdapSyncService 曾把 password_hash 全库写为字面量 "LDAP_SYNCED"（已知口令），
        // 现虽已改为 bcrypt(随机UUID)，仍需拦住库里可能存在的存量明文账号。
        if (LDAP_PLACEHOLDER.equals(user.getPasswordHash())) {
            auditService.log(user.getTenantId(), user.getId().toString(), user.getUsername(),
                    "auth.login.failed", "auth", user.getId().toString(),
                    Map.of("username", user.getUsername(), "reason", "ldap_placeholder_password"));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "该账号由 LDAP 同步创建，请通过企业身份提供商（IdP）登录");
        }

        // A-2：外部托管账号（钉钉/企微/飞书/LDAP/OIDC）没有本地密码，必须走 SSO。
        // 修复前这里直接落到 passwordEncoder.matches(password, null) —— BCrypt 对 null 哈希
        // 只返回 false 并打一条 WARN，用户看到的是笼统的「账号或密码错误」，
        // 无法判断到底是密码错还是「本就不该用密码登录」。
        if (!user.isPasswordSet() || user.getPasswordHash() == null) {
            auditService.log(user.getTenantId(), user.getId().toString(), user.getUsername(),
                    "auth.login.failed", "auth", user.getId().toString(),
                    Map.of("username", user.getUsername(), "reason", "externally_managed_account"));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "该账号由企业平台托管，请使用 SSO 登录");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditService.log(user.getTenantId(), user.getId().toString(), user.getUsername(),
                    "auth.login.failed", "auth", user.getId().toString(),
                    Map.of("username", user.getUsername(), "reason", "bad_password"));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }

        // A-5：禁用/离职账号不得再获得新会话（签发前拦截，避免"签完再失效"的窗口）
        if (!user.isActiveEmployee()) {
            auditService.log(user.getTenantId(), user.getId().toString(), user.getUsername(),
                    "auth.login.failed", "auth", user.getId().toString(),
                    Map.of("username", user.getUsername(), "reason", "account_disabled"));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已被禁用或已离职");
        }

        // 🔒-SaaS-P0 R1: 从角色体系加载 roles，写入 JWT claims
        // R5: 用批量查询替代 N+1，传入 tenantId 作租户维度
        var roleNames = roleRepository.findRoleNamesByUserId(user.getId(), user.getTenantId());

        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getUsername(), user.getTenantId(), roleNames);

        String refreshToken = refreshTokenService.issue(user.getId());

        auditService.log(user.getTenantId(), user.getId().toString(), user.getUsername(),
                "auth.login.success", "auth", user.getId().toString(),
                Map.of("username", user.getUsername(), "userId", user.getId().toString(),
                        "tenantId", user.getTenantId()));

        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "access_token", accessToken,
                        "refresh_token", refreshToken,
                        "token_type", "Bearer",
                        "expires_in", jwtService.getAccessTtl().toSeconds(),
                        "user", Map.of(
                                "id", user.getId().toString(),
                                "username", user.getUsername(),
                                "display_name", user.getDisplayName() != null ? user.getDisplayName() : user.getUsername(),
                                "tenant_id", user.getTenantId(),
                                "roles", roleNames
                        ),
                        "issued_at", Instant.now().toString()
                )
        ));
    }

    /**
     * 刷新 access token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody @Valid RefreshRequest request) {
        UUID userId = refreshTokenService.consume(request.refreshToken());
        if (userId == null) {
            auditService.log("unknown", "anonymous", "anonymous",
                    "auth.refresh.failed", "auth", "unknown",
                    Map.of("reason", "invalid_refresh_token"));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh token 无效或已过期");
        }

        UserEntity user = userRepository.findById(userId)
                .orElse(null);
        
        if (user == null) {
            auditService.log("unknown", userId.toString(), "anonymous",
                    "auth.refresh.failed", "auth", userId.toString(),
                    Map.of("userId", userId.toString(), "reason", "user_not_found"));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在");
        }

        // A-5：禁用/离职账号的 refresh 同样必须失效，否则等于永久有效
        // （access 只有 15 分钟，refresh 有 7 天 —— 只拦 access 是不够的）。
        if (!user.isActiveEmployee()) {
            auditService.log(user.getTenantId(), user.getId().toString(), user.getUsername(),
                    "auth.refresh.failed", "auth", user.getId().toString(),
                    Map.of("username", user.getUsername(), "reason", "account_disabled"));
            refreshTokenService.revokeAll(user.getId());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已被禁用或已离职");
        }

        // 🔒-SaaS-P0 R1: 从角色体系加载 roles，写入 JWT claims
        // R5: 用批量查询替代 N+1，传入 tenantId 作租户维度
        var refreshRoleNames = roleRepository.findRoleNamesByUserId(user.getId(), user.getTenantId());

        String newAccessToken = jwtService.issueAccessToken(
                user.getId(), user.getUsername(), user.getTenantId(), refreshRoleNames);
        String newRefreshToken = refreshTokenService.issue(user.getId());

        auditService.log(user.getTenantId(), user.getId().toString(), user.getUsername(),
                "auth.refresh.success", "auth", user.getId().toString(),
                Map.of("username", user.getUsername(), "tenantId", user.getTenantId()));

        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "access_token", newAccessToken,
                        "refresh_token", newRefreshToken,
                        "token_type", "Bearer",
                        "expires_in", jwtService.getAccessTtl().toSeconds()
                )
        ));
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {}

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}

    /**
     * 改密码(US-502). 需登录.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/password")
    public Map<String, Object> changePassword(
            @RequestBody @Valid ChangePasswordRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        var dbUser = userRepository.findById(user.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User 不存在"));
        // A-2：外部托管账号没有本地密码，改密前提（旧密码校验）不成立
        if (!dbUser.isPasswordSet() || dbUser.getPasswordHash() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "该账号由企业平台托管，请使用 SSO 登录");
        }
        if (!passwordEncoder.matches(request.oldPassword(), dbUser.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "旧密码错误");
        }
        dbUser.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        dbUser.setPasswordSet(true);
        userRepository.save(dbUser);
        return Map.of("code", 0, "message", "密码修改成功");
    }

    public record ChangePasswordRequest(
            @NotBlank String oldPassword,
            @NotBlank String newPassword
    ) {}

    /**
     * 当前登录用户信息(US-502 个人中心).
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthenticatedUser user) {
        var dbUser = userRepository.findById(user.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User 不存在"));
        var roleNames = userRoleRepository.findByIdUserId(user.userId())
                .stream()
                .map(ur -> roleRepository.findById(ur.getId().getRoleId()))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .map(r -> r.getName())
                .toList();
        return Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "id", dbUser.getId().toString(),
                        "username", dbUser.getUsername(),
                        "tenant_id", dbUser.getTenantId(),
                        "roles", roleNames,
                        "created_at", dbUser.getCreatedAt().toString()
                )
        );
    }
}
