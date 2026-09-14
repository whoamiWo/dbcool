package com.nocobase.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
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

    public AuthController(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * 登录.
     */
    @PostMapping("/login")
    @Transactional
    public ResponseEntity<Map<String, Object>> login(@RequestBody @Valid LoginRequest request) {
        UserEntity user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "账号或密码错误"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }

        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getUsername(), user.getTenantId());
        String refreshToken = refreshTokenService.issue(user.getId());

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
                                "roles", new String[]{"admin"}
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
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh token 无效或已过期");
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "用户不存在"));

        String newAccessToken = jwtService.issueAccessToken(
                user.getId(), user.getUsername(), user.getTenantId());
        String newRefreshToken = refreshTokenService.issue(user.getId());

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
    @PostMapping("/password")
    public Map<String, Object> changePassword(
            @RequestBody @Valid ChangePasswordRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        var dbUser = userRepository.findById(user.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User 不存在"));
        if (!passwordEncoder.matches(request.oldPassword(), dbUser.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "旧密码错误");
        }
        dbUser.setPasswordHash(passwordEncoder.encode(request.newPassword()));
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
