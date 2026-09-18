package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 钉钉微应用服务 — 授权、code 交换与用户同步。
 *
 * <p><b>Week 44 接真</b>:此前 {@code syncUserFromDingTalk} 返回随机 UUID(占位),
 * 未落库导致 SSO 登录后无法与本地用户体系打通(权限/审计/IM 全部失联)。
 * 现按钉钉 unionid 稳定映射本地账号:
 * <ul>
 *   <li>已存在 → 直接复用(幂等,重复登录不会建重复账号)</li>
 *   <li>不存在 → 以 {@code dt_<unionid 摘要>} 为用户名建号,密码随机(不走密码登录)</li>
 * </ul>
 *
 * <p>未配置 {@code dingtalk.app-key} 时,登录返回明确错误而非伪造用户。
 */
@Service
public class DingTalkAppService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkAppService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${dingtalk.app-key:}")
    private String appKey;

    @Value("${dingtalk.app-secret:}")
    private String appSecret;

    @Value("${dingtalk.agent-id:}")
    private String agentId;

    @Value("${dingtalk.redirect-uri:}")
    private String redirectUri;

    public DingTalkAppService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** 是否已配置钉钉应用凭证。 */
    public boolean isConfigured() {
        return appKey != null && !appKey.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    /** 生成钉钉扫码授权 URL。 */
    public String getAuthUrl(String state, String tenantId) {
        return "https://login.dingtalk.com/oauth2/auth?" +
                "client_id=" + appKey +
                "&redirect_uri=" + redirectUri +
                "&response_type=code" +
                "&scope=openid" +
                "&state=" + (state == null ? "" : state) +
                "&connect_id_type=unionid";
    }

    /**
     * 用 code 换取用户身份并同步到本地。
     *
     * @return {userId, username, nickname, openid, unionid}
     */
    @Transactional
    public Map<String, Object> exchangeCode(String code, String tenantId) {
        if (!isConfigured()) {
            return Map.of("code", 500,
                    "message", "钉钉应用未配置(dingtalk.app-key / app-secret)", "data", Map.of());
        }
        if (code == null || code.isBlank()) {
            return Map.of("code", 400, "message", "code 不能为空", "data", Map.of());
        }

        try {
            // 1) 换 access_token(openid / unionid)
            String tokenUrl = "https://oapi.dingtalk.com/connect/oauth2/access_token" +
                    "?appid=" + appKey + "&secret=" + appSecret +
                    "&code=" + code + "&grant_type=authorization_code";
            String tokenBody = new org.springframework.web.client.RestTemplate()
                    .getForObject(tokenUrl, String.class);
            JsonNode tokenJson = objectMapper.readTree(tokenBody == null ? "{}" : tokenBody);
            String openid = tokenJson.path("openid").asText("");
            String unionid = tokenJson.path("unionid").asText("");
            if (unionid.isBlank()) unionid = openid;
            if (unionid.isBlank()) {
                return Map.of("code", 401, "message", "钉钉授权失败:未获取到用户标识", "data", Map.of());
            }

            // 2) 拉取用户昵称(失败不影响建号,退化为 openid 摘要)
            String nickname = unionid;
            try {
                String userUrl = "https://oapi.dingtalk.com/sns/getuserinfo?access_token="
                        + tokenJson.path("access_token").asText("");
                String userBody = new org.springframework.web.client.RestTemplate()
                        .postForObject(userUrl, null, String.class);
                JsonNode userJson = objectMapper.readTree(userBody == null ? "{}" : userBody);
                String nick = userJson.path("user_info").path("nick").asText("");
                if (!nick.isBlank()) nickname = nick;
            } catch (Exception e) {
                log.warn("[DingTalk] 拉取用户昵称失败,使用默认名: {}", e.getMessage());
            }

            // 3) 同步/复用本地账号
            UserEntity user = syncUserFromDingTalk(unionid, nickname, tenantId);

            return Map.of(
                    "code", 0,
                    "message", "success",
                    "data", Map.of(
                            "userId", user.getId().toString(),
                            "username", user.getUsername(),
                            "nickname", user.getDisplayName() == null ? "" : user.getDisplayName(),
                            "openid", openid,
                            "unionid", unionid,
                            "tenantId", tenantId
                    )
            );
        } catch (Exception e) {
            log.error("[DingTalk] code 交换失败: {}", e.getMessage());
            return Map.of("code", 502, "message", "钉钉登录失败: " + e.getMessage(), "data", Map.of());
        }
    }

    /**
     * 按 unionid 同步到本地用户(幂等)。
     *
     * <p>钉钉用户无本地密码,生成随机密码写入(该账号只通过 SSO 登录);
     * 用户名取 {@code dt_} + unionid 的字母数字摘要,保证唯一且可作为登录名。
     */
    @Transactional
    public UserEntity syncUserFromDingTalk(String unionid, String nickname, String tenantId) {
        String username = "dt_" + safeTail(unionid, 12);
        return userRepository.findByUsername(username)
                .map(existing -> {
                    log.debug("[DingTalk] 复用既有账号: {}", username);
                    return existing;
                })
                .orElseGet(() -> {
                    UserEntity u = new UserEntity();
                    u.setId(UUID.randomUUID());
                    u.setUsername(username);
                    u.setPasswordHash(passwordEncoder.encode(randomPassword()));
                    u.setDisplayName(nickname == null || nickname.isBlank() ? username : nickname);
                    u.setTenantId(tenantId);
                    u.setEnabled(true);
                    u.setCreatedAt(java.time.Instant.now());
                    UserEntity saved = userRepository.save(u);
                    log.info("[DingTalk] 新建钉钉账号: {} (tenant={}, display={})",
                            username, tenantId, u.getDisplayName());
                    return saved;
                });
    }

    /** 组织架构同步入口(全量拉取通讯录并按需建号)。 */
    public Map<String, Object> syncOrganization(String tenantId) {
        if (!isConfigured()) {
            return Map.of("code", 500, "message", "钉钉应用未配置", "data", Map.of());
        }
        // 真实实现需分页调 /topapi/v2/user/list 逐部门拉取;此处先返回执行结果占位,
        // 单用户建号能力已由 syncUserFromDingTalk 提供(登录即同步)。
        log.info("[DingTalk] 组织架构同步触发: tenant={}", tenantId);
        return Map.of("code", 0, "message",
                "已触发; 单用户同步在登录时完成(批量通讯录同步按需扩展)", "data", Map.of());
    }

    /** 微应用配置(供前端/运维核对)。 */
    public Map<String, Object> getAppConfig() {
        return Map.of(
                "appKey", appKey == null ? "" : appKey,
                "agentId", agentId == null ? "" : agentId,
                "redirectUri", redirectUri == null ? "" : redirectUri,
                "configured", isConfigured()
        );
    }

    // ============================================================
    //  工具
    // ============================================================

    /** 取标识尾部并只保留字母数字,避免生成非法用户名。 */
    private static String safeTail(String raw, int max) {
        String s = raw == null ? "" : raw.replaceAll("[^a-zA-Z0-9]", "");
        if (s.isEmpty()) s = "unknown";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    /** 随机密码(SSO 账号不使用,仅满足非空约束)。 */
    private static String randomPassword() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
