package com.nocobase.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
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
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
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
}
