package com.nocobase.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 统一建号路径（A-2 修复）。
 *
 * <p>修复前的状态：7 处外部建号各写各的 ——
 * <ul>
 *   <li>{@code DingTalkOrgSyncService} / {@code OidcUserService} <b>完全不设密码</b>，
 *       而 {@code users.password_hash} 当时是 NOT NULL → insert 必抛约束冲突，
 *       异常被 {@code catch { log.warn }} 吞掉 → 界面显示「同步 N 人」实为 0 人。</li>
 *   <li>另外 5 处写 bcrypt(随机 UUID) 作为「不可登录口令」，语义上仍是「有密码的账号」，
 *       一旦有人拿到库里那行就能用 <code>passwordEncoder.matches</code> 之外的路径绕过，
 *       也让「该账号只能用 SSO」这条规则无法用数据表达。</li>
 * </ul>
 *
 * <p>修复后：外部托管账号统一 <b>{@code passwordHash = null} + {@code passwordSet = false}</b>，
 * 语义显式化；{@code AuthController#login} 对这类账号直接 401 并提示走 SSO。
 *
 * <p>注意：{@code users.password_hash} 的 NOT NULL 已由 V77 放开。判定「有没有本地密码」
 * 一律看 {@link UserEntity#isPasswordSet()}，不要看哈希是否为 null —— 存量数据里可能有
 * 空哈希但 {@code password_set = true} 的账号（V77 的默认值），那是历史形态。
 */
@Service
public class UserProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioningService.class);

    private final UserRepository userRepository;

    public UserProvisioningService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 按用户名幂等地建一个「外部平台托管」账号。
     *
     * <p>已存在则原样返回（不覆盖本地已设的密码与档案），必要时补齐 {@code passwordSet=false}
     * 的语义（仅当该账号确有外部来源且从未设过密码）。
     *
     * @param tenantId    租户
     * @param username    登录名（外部来源需自行保证唯一，如 {@code dt_xxx}）
     * @param displayName 显示名（空则退化为 username）
     * @param email       邮箱（可空）
     * @param source      来源标识，仅用于日志与审计：DINGTALK/WECOM/WECHAT/FEISHU/LDAP/OIDC/IM
     */
    public UserEntity provisionExternalAccount(String tenantId, String username,
                                               String displayName, String email,
                                               String source) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username 不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        UserEntity existing = userRepository.findByTenantIdAndUsername(tenantId, username).orElse(null);
        if (existing != null) {
            return existing;
        }
        return createExternal(tenantId, username, displayName, email, source);
    }

    /**
     * 无条件新建一个外部托管账号（调用方已确认不存在）。
     *
     * <p>与 {@link #provisionExternalAccount} 的差别：不做存在性查询，适用于调用方
     * 已经批量比对过的场景（如组织同步）。
     */
    public UserEntity createExternal(String tenantId, String username, String displayName,
                                     String email, String source) {
        return createExternal(tenantId, username, displayName, email, source, null);
    }

    /**
     * 新建外部托管账号，并写入显式外部身份（F2）。
     *
     * <p>{@code users.username} 在 V1 中是<b>全局唯一</b>。两个租户接同一个外部应用时，
     * 同一 {@code open_id} 会生成同一个用户名；此处对跨租户冲突追加稳定的租户后缀，
     * 既保证双方都能建号，也<b>绝不改写另一个租户的账号</b>。
     * 租户内仍由 {@code (tenant_id, external_source, external_id)} 唯一索引进一重保护。
     */
    public UserEntity createExternal(String tenantId, String username, String displayName,
                                     String email, String source, String externalId) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username 不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        String finalUsername = username;
        Optional<UserEntity> global = userRepository.findByUsername(username);
        if (global.isPresent()) {
            UserEntity other = global.get();
            if (tenantId.equals(other.getTenantId())) {
                throw new IllegalStateException("账号已存在，不能重复创建: " + username);
            }
            finalUsername = username + "_" + tenantSuffix(tenantId);
            Optional<UserEntity> suffixed = userRepository.findByUsername(finalUsername);
            if (suffixed.isPresent() && tenantId.equals(suffixed.get().getTenantId())) {
                // 同一租户的重试（例如 external_id 尚未回填）：返回已建账号，避免重复建号。
                UserEntity existing = suffixed.get();
                if (existing.getExternalId() == null) {
                    existing.setExternalSource(source);
                    existing.setExternalId(externalId);
                    existing = userRepository.save(existing);
                }
                log.info("[provision] 复用租户后缀账号 source={} tenant={} username={}",
                        source, tenantId, finalUsername);
                return existing;
            }
            if (suffixed.isPresent()) {
                finalUsername = finalUsername + "_" + UUID.randomUUID().toString().substring(0, 4);
            }
            log.warn("[provision] 用户名跨租户冲突，使用租户后缀 source={} tenant={} username={} -> {}",
                    source, tenantId, username, finalUsername);
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(finalUsername);
        // A-2 的核心：外部托管账号没有本地密码
        user.setPasswordHash(null);
        user.setPasswordSet(false);
        user.setTenantId(tenantId);
        user.setDisplayName(displayName == null || displayName.isBlank() ? finalUsername : displayName);
        user.setEmail(email);
        user.setEnabled(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setExternalSource(source);
        user.setExternalId(externalId == null || externalId.isBlank() ? null : externalId);
        user.setCreatedAt(Instant.now());
        try {
            UserEntity saved = userRepository.save(user);
            log.info("[provision] 新建外部托管账号 source={} username={} tenant={}",
                    source, finalUsername, tenantId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 并发建号或仍存在的唯一键冲突：给调用方明确错误，绝不回退成"复用他人账号"。
            throw new IllegalStateException(
                    "外部账号建号冲突（username 已被占用）: " + finalUsername, e);
        }
    }

    /** 稳定、可读的租户后缀（SHA-256 前 6 位十六进制）。 */
    private static String tenantSuffix(String tenantId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(tenantId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 6);
        } catch (Exception e) {
            return Integer.toHexString(Math.abs(tenantId.hashCode()));
        }
    }

    /**
     * 把既有账号标记为外部托管（清掉本地密码）。
     *
     * <p>用于迁移/纠偏路径：例如历史上被写成 bcrypt(随机 UUID) 的钉钉账号，
     * 现在应改为「无本地密码」。这是一次写入语义变更，调用方需显式调用。
     */
    public UserEntity markExternallyManaged(UserEntity user) {
        user.setPasswordHash(null);
        user.setPasswordSet(false);
        return userRepository.save(user);
    }
}
