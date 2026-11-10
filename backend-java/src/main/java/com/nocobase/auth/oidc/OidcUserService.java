package com.nocobase.auth.oidc;

import com.nocobase.auth.RoleRepository;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserProvisioningService;
import com.nocobase.auth.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * OIDC 用户映射 / JIT 建号。
 *
 * <p>N2 明确只支持 {@code tenant_default}。唯一登录身份是
 * {@code (tenant_default, OIDC, sub)}，email、username 与 IdP groups 均不参与授权。
 */
@Service
public class OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(OidcUserService.class);
    private static final String TENANT_ID = "tenant_default";
    private static final String EXTERNAL_SOURCE = "OIDC";
    private static final int MAX_SUBJECT_BYTES = 128;

    private final UserRepository userRepository;
    private final UserProvisioningService provisioningService;
    private final RoleRepository roleRepository;

    public OidcUserService(UserRepository userRepository,
                           UserProvisioningService provisioningService,
                           RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.provisioningService = provisioningService;
        this.roleRepository = roleRepository;
    }

    /**
     * 把 OIDC sub 映射为默认租户用户，未绑定时才 JIT 建号。
     *
     * @throws ResponseStatusException sub 缺失时 401；已绑定用户禁用/离职时 403
     */
    @Transactional
    public UserEntity mapOrCreate(OidcUser oidcUser) {
        if (oidcUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC 用户信息缺失");
        }
        String subject = oidcUser.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC sub 缺失");
        }
        if (subject.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SUBJECT_BYTES) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC sub 超出长度限制");
        }
        if (oidcUser.getEmail() != null && oidcUser.getEmail().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OIDC email 超出长度限制");
        }

        Optional<UserEntity> bound = userRepository.findByTenantIdAndExternalSourceAndExternalId(
                TENANT_ID, EXTERNAL_SOURCE, subject);
        if (bound.isPresent()) {
            return assertActive(bound.get());
        }
        return createUser(oidcUser, subject);
    }

    private UserEntity assertActive(UserEntity user) {
        if (!user.isActiveEmployee()) {
            log.warn("[OIDC] 用户已禁用或离职，拒绝登录 userId={}", user.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已被禁用或离职");
        }
        return user;
    }

    private UserEntity createUser(OidcUser oidcUser, String subject) {
        String email = oidcUser.getEmail();
        String preferredUsername = oidcUser.getPreferredUsername();
        String username = "oidc_" + UUID.nameUUIDFromBytes(
                        (EXTERNAL_SOURCE + ":" + subject).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 16);
        String displayName = oidcUser.getFullName();
        if (displayName == null || displayName.isBlank()) {
            displayName = preferredUsername == null || preferredUsername.isBlank()
                    ? username : preferredUsername;
        }

        if (displayName.length() > 128) displayName = displayName.substring(0, 128);
        UserEntity saved = provisioningService.createExternal(
                TENANT_ID, username, displayName, email, EXTERNAL_SOURCE, subject);
        log.info("[OIDC] JIT 建号成功 userId={} username={} tenant={}",
                saved.getId(), saved.getUsername(), TENANT_ID);
        return saved;
    }

    /** 本地角色是 OIDC JWT 的唯一角色来源；JIT 用户默认没有角色。 */
    public List<String> rolesOf(UUID userId) {
        return roleRepository.findRoleNamesByUserId(userId, TENANT_ID);
    }
}
