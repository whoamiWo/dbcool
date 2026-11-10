package com.nocobase.auth.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Issues and exchanges one-time OIDC codes without persisting raw codes or tokens. */
@Service
public class OidcLoginCodeService {

    static final Duration CODE_TTL = Duration.ofSeconds(60);
    private static final int CODE_BYTES = 32;
    private static final int MAX_CODE_LENGTH = 256;

    private final OidcLoginCodeRepository codeRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public OidcLoginCodeService(OidcLoginCodeRepository codeRepository,
                                UserRepository userRepository,
                                JwtService jwtService,
                                ObjectMapper objectMapper) {
        this.codeRepository = codeRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    /** Returns the raw code once; only its SHA-256 hash and local login binding are saved. */
    @Transactional
    public String issue(UUID userId, String tenantId, List<String> roles) {
        byte[] random = new byte[CODE_BYTES];
        secureRandom.nextBytes(random);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(random);

        Instant now = Instant.now();
        OidcLoginCodeEntity loginCode = new OidcLoginCodeEntity();
        loginCode.setId(UUID.randomUUID());
        loginCode.setCodeHash(sha256(code));
        loginCode.setUserId(userId);
        loginCode.setTenantId(tenantId);
        loginCode.setRolesJson(serializeRoles(roles));
        loginCode.setCreatedAt(now);
        loginCode.setExpiresAt(now.plus(CODE_TTL));
        codeRepository.save(loginCode);
        return code;
    }

    /**
     * Consumes the code before issuing a token. There is deliberately no surrounding exchange
     * transaction: a disabled user or signing failure must not roll back consumption.
     */
    public ExchangeResult exchange(String code) {
        if (code == null || code.isBlank() || code.length() > MAX_CODE_LENGTH) {
            throw invalidCode();
        }
        String codeHash = sha256(code);
        if (codeRepository.consume(codeHash) != 1) {
            throw invalidCode();
        }
        OidcLoginCodeEntity consumed = codeRepository.findByCodeHash(codeHash)
                .orElseThrow(OidcLoginCodeService::invalidCode);
        List<String> roles = deserializeRoles(consumed.getRolesJson());

        UserEntity user = userRepository.findByIdAndTenantId(consumed.getUserId(), consumed.getTenantId())
                .filter(UserEntity::isActiveEmployee)
                .orElseThrow(OidcLoginCodeService::invalidCode);
        String accessToken = jwtService.issueAccessToken(
                consumed.getUserId(), user.getUsername(), consumed.getTenantId(), roles);
        return new ExchangeResult(accessToken, jwtService.getAccessTtl().toSeconds(), user, roles);
    }

    private String serializeRoles(List<String> roles) {
        try {
            return objectMapper.writeValueAsString(roles == null ? List.of() : List.copyOf(roles));
        } catch (Exception e) {
            throw new IllegalStateException("无法保存 OIDC 本地角色绑定", e);
        }
    }

    private List<String> deserializeRoles(String rolesJson) {
        try {
            JsonNode root = objectMapper.readTree(rolesJson);
            if (!root.isArray()) {
                throw invalidCode();
            }
            List<String> roles = new ArrayList<>();
            for (JsonNode role : root) {
                if (!role.isTextual() || role.textValue().isBlank()) {
                    throw invalidCode();
                }
                roles.add(role.textValue());
            }
            return List.copyOf(roles);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw invalidCode();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static ResponseStatusException invalidCode() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC 登录 code 无效或已过期");
    }

    public record ExchangeResult(String accessToken, long expiresIn, UserEntity user, List<String> roles) {}
}
