package com.nocobase.auth.oidc;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.tenant.TenantContext;
import java.time.Instant;
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
 * PHASE10: OIDC 用户映射 / JIT 建号。
 *
 * <p>映射优先级（首次登录）：
 * <ol>
 *   <li>按 email 匹配本地已有用户（企业内 IdP 邮箱通常与本地账号一致）；</li>
 *   <li>按 username（preferred_username）匹配；</li>
 *   <li>都未匹配 → JIT 建号（Just-In-Time Provisioning），落当前租户。</li>
 * </ol>
 *
 * <p><b>安全约束</b>：
 * <ul>
 *   <li>JIT 建号的用户不设置密码（passwordHash 留空），只能走 OIDC 登录，
 *       避免凭空产生可账密登录的账号；</li>
 *   <li>已存在但被禁用（enabled=false）的用户拒绝登录；</li>
 *   <li>建号落在 {@link TenantContext} 当前租户，不硬编码。</li>
 * </ul>
 */
@Service
public class OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(OidcUserService.class);

    private final UserRepository userRepository;

    public OidcUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 把 OIDC 用户映射为本地用户（不存在则 JIT 建号）。
     *
     * @param oidcUser IdP 返回的用户信息
     * @return 本地用户实体
     * @throws ResponseStatusException 用户被禁用或映射失败
     */
    @Transactional
    public UserEntity mapOrCreate(OidcUser oidcUser) {
        if (oidcUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC 用户信息缺失");
        }
        String email = oidcUser.getEmail();
        String username = oidcUser.getPreferredUsername();
        String subject = oidcUser.getSubject();

        if ((email == null || email.isBlank())
                && (username == null || username.isBlank())) {
            // sub 必不为空，用它兜底，保证一定能建立映射
            username = subject;
        }

        // 1. 按 email 匹配
        if (email != null && !email.isBlank()) {
            Optional<UserEntity> byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) {
                return assertEnabled(byEmail.get());
            }
        }
        // 2. 按 username 匹配
        if (username != null && !username.isBlank()) {
            Optional<UserEntity> byName = userRepository.findByUsername(username);
            if (byName.isPresent()) {
                return assertEnabled(byName.get());
            }
        }
        // 3. JIT 建号
        return createUser(oidcUser, username, email);
    }

    private UserEntity assertEnabled(UserEntity user) {
        if (!user.isEnabled()) {
            log.warn("[OIDC] 用户已禁用，拒绝登录 userId={}", user.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已被禁用");
        }
        return user;
    }

    private UserEntity createUser(OidcUser oidcUser, String username, String email) {
        String tenantId = TenantContext.currentTenantId();
        // 用户名冲突时加随机后缀，避免唯一键冲突
        String finalUsername = username;
        if (finalUsername == null || finalUsername.isBlank()) {
            finalUsername = "oidc_" + UUID.randomUUID().toString().substring(0, 8);
        } else if (userRepository.findByUsername(finalUsername).isPresent()) {
            finalUsername = finalUsername + "_" + UUID.randomUUID().toString().substring(0, 6);
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(finalUsername);
        // 不设密码：JIT 用户只能通过 OIDC 登录
        user.setPasswordHash(null);
        user.setTenantId(tenantId);
        user.setDisplayName(oidcUser.getFullName() != null ? oidcUser.getFullName() : finalUsername);
        user.setEmail(email);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());

        UserEntity saved = userRepository.save(user);
        log.info("[OIDC] JIT 建号成功 userId={} username={} tenant={}",
                saved.getId(), finalUsername, tenantId);
        return saved;
    }

    /** 取用户角色名列表（供 JWT claims；此处返回空列表由调用方按需扩展）。 */
    public List<String> rolesOf(UUID userId, String tenantId) {
        return List.of();
    }
}
