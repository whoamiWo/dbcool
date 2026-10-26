package com.nocobase.integration.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.integration.common.ExternalUserMappingEntity;
import com.nocobase.integration.common.ExternalUserMappingRepository;
import com.nocobase.integration.credential.TenantCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 微信公众号 / 小程序应用服务 — OAuth 授权、code2Session 与用户同步。
 *
 * <p>参考 WeComAppService 实现：
 * <ul>
 *   <li>公众号网页授权（snsapi_userinfo）：授权 URL 生成 → code 换 access_token → 拉取 openid/用户信息</li>
 *   <li>小程序 code2Session：js_code 换 openid/session_key/unionid</li>
 *   <li>已存在 → 直接复用 (幂等，重复登录不会建重复账号)</li>
 *   <li>不存在 → 以 {@code wechat_<openid 摘要>} 为用户名建号，密码随机 (不走密码登录)</li>
 * </ul>
 */
@Service
public class WeChatAppService {

    private static final Logger log = LoggerFactory.getLogger(WeChatAppService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** PHASE99 T1: 租户级凭证服务（优先于全局 @Value） */
    private final TenantCredentialService credentialService;

    /** PHASE96 T1: openid → 本地用户持久映射（微信身份的唯一真相） */
    private final ExternalUserMappingRepository externalUserMappingRepository;

    // 以下 @Value 字段保留为**回退默认值**：租户级未配置时使用，存量部署零破坏
    @Value("${wechat.app-id:}")
    private String appId;

    @Value("${wechat.app-secret:}")
    private String appSecret;

    @Value("${wechat.redirect-uri:}")
    private String redirectUri;

    /** access_token 缓存（微信 token 有效期 7200s，缓存 10min 留 30s buffer） */
    private volatile String cachedToken;
    private volatile long cachedTokenExpireAt = 0;

    public WeChatAppService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                            RestTemplate restTemplate, TenantCredentialService credentialService,
                            ExternalUserMappingRepository externalUserMappingRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.restTemplate = restTemplate;
        this.credentialService = credentialService;
        this.externalUserMappingRepository = externalUserMappingRepository;
    }

    /**
     * 按 key 解析凭证：租户级优先，回退全局 @Value。
     *
     * @param tenantId 租户 ID；传 null 时只取全局（用于无租户上下文的场景）
     */
    private Optional<String> resolveForKey(String key, String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            Optional<String> tenantVal = credentialService.get(tenantId, "wechat", key);
            if (tenantVal.isPresent()) {
                return tenantVal;
            }
        }
        // 回退全局 @Value
        String fallback = switch (key) {
            case "app-id" -> appId;
            case "app-secret" -> appSecret;
            case "redirect-uri" -> redirectUri;
            default -> null;
        };
        return (fallback != null && !fallback.isBlank()) ? Optional.of(fallback) : Optional.empty();
    }

    public String getAppId() {
        return resolveForKey("app-id", null).orElse("");
    }

    public String getAppSecret() {
        return resolveForKey("app-secret", null).orElse("");
    }

    /** 是否已配置微信公众号/小程序凭证。 */
    public boolean isConfigured() {
        return resolveForKey("app-id", null).isPresent()
                && resolveForKey("app-secret", null).isPresent();
    }

    /**
     * 获取微信公众号 access_token（调用 /cgi-bin/token），带本地缓存。
     * <p>微信 token 有效期 7200s，缓存 10min 留 30s buffer，避免高频调用触发频率限制。</p>
     */
    public String getAccessToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < cachedTokenExpireAt) {
            return cachedToken;
        }
        String appId = resolveForKey("app-id", null).orElse("");
        String appSecret = resolveForKey("app-secret", null).orElse("");
        if (appId.isBlank() || appSecret.isBlank()) {
            throw new IllegalStateException("微信 app-id 或 app-secret 未配置");
        }
        String url = "https://api.weixin.qq.com/cgi-bin/token" +
                "?appid=" + appId + "&secret=" + appSecret + "&grant_type=client_credential";
        String body = restTemplate.getForObject(url, String.class);
        try {
            JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
            String token = json.path("access_token").asText("");
            if (token.isBlank()) {
                throw new RuntimeException("获取 access_token 失败：" + body);
            }
            cachedToken = token;
            cachedTokenExpireAt = now + 600_000; // 10min - 30s buffer
            return token;
        } catch (Exception e) {
            throw new RuntimeException("解析 access_token 失败", e);
        }
    }

    /**
     * 通过 code 换取用户信息并同步到本地（公众号网页授权）。
     *
     * <p>流程：
     * <ol>
     *   <li>用 code 调用 /sns/oauth2/access_token 获取 openid/access_token</li>
     *   <li>再调用 /sns/userinfo 获取详细信息（nickname, headimgurl, province, city, gender）</li>
     *   <li>按 openid 在本地查找/创建用户</li>
     * </ol>
     */
    @Transactional
    public UserEntity loginFromWeChat(String code) {
        if (!isConfigured()) {
            throw new IllegalStateException("微信公众号应用未配置");
        }

        String appId = getAppId();
        String appSecret = getAppSecret();

        // 1. 通过 code 获取 access_token 和 openid
        String tokenUrl = "https://api.weixin.qq.com/sns/oauth2/access_token" +
                "?appid=" + appId + "&secret=" + appSecret + "&code=" + code + "&grant_type=authorization_code";
        String tokenBody = restTemplate.getForObject(tokenUrl, String.class);
        JsonNode tokenJson = null;
        try {
            tokenJson = objectMapper.readTree(tokenBody == null ? "{}" : tokenBody);
        } catch (Exception e) {
            throw new RuntimeException("解析 access_token 失败", e);
        }
        String accessToken = tokenJson.path("access_token").asText("");
        String openid = tokenJson.path("openid").asText("");
        if (accessToken.isBlank() || openid.isBlank()) {
            throw new RuntimeException("获取 access_token/openid 失败：" + tokenBody);
        }

        // 2. 获取用户信息
        String userInfoUrl = "https://api.weixin.qq.com/sns/userinfo" +
                "?access_token=" + accessToken + "&openid=" + openid + "&lang=zh_CN";
        String userInfoBody = restTemplate.getForObject(userInfoUrl, String.class);
        JsonNode userInfoJson = null;
        try {
            userInfoJson = objectMapper.readTree(userInfoBody == null ? "{}" : userInfoBody);
        } catch (Exception e) {
            throw new RuntimeException("解析用户信息失败", e);
        }
        String nickname = userInfoJson.path("nickname").asText(openid);
        String headimgurl = userInfoJson.path("headimgurl").asText("");
        String province = userInfoJson.path("province").asText("");
        String city = userInfoJson.path("city").asText("");
        String gender = userInfoJson.path("sex").asText("");
        String unionId = userInfoJson.path("unionid").asText("");
        if (unionId.isBlank()) unionId = openid;

        // openid 是微信身份的唯一真相：优先走 ExternalUserMapping（已绑定则直接取本地用户）
        UserEntity bound = resolveByMapping("wechat", openid, "system");
        if (bound != null) {
            log.info("[WeChat] 映射已存在：openid={}", openid);
            return bound;
        }

        // 按 openid 哈希后的用户名查找本地用户（未建映射的旧数据兜底）
        String username = "wechat_" + safeTail(openid, 12);
        UserEntity existing = userRepository.findByUsername(username).orElse(null);
        if (existing != null) {
            persistMapping("wechat", openid, "system", existing.getId(), nickname);
            log.info("[WeChat] 用户已存在并补建映射：username={}, openid={}", username, openid);
            return existing;
        }

        // 创建本地用户（密码随机，不走密码登录；与企微 wecom_ 前缀模式对称）
        String randomPassword = UUID.randomUUID().toString();
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(randomPassword));
        user.setTenantId("system");
        user.setDisplayName(nickname);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
        persistMapping("wechat", openid, "system", user.getId(), nickname);
        log.info("[WeChat] 新建用户+映射：username={}, openid={}", username, openid);
        return user;
    }

    /**
     * 按 (platform, externalUserId, tenantId) 查映射，命中则返回对应本地用户。
     * 未命中或本地用户不存在返回 null（由调用方走建号路径）。
     */
    private UserEntity resolveByMapping(String platform, String externalUserId, String tenantId) {
        var mapping = externalUserMappingRepository
                .findByPlatformAndExternalUserIdAndTenantId(platform, externalUserId, tenantId);
        if (mapping.isEmpty() || mapping.get().getLocalUserId() == null) {
            return null;
        }
        return userRepository.findById(mapping.get().getLocalUserId()).orElse(null);
    }

    /** 落/更新 openid → 本地用户映射（幂等：已存在则刷新本地用户 ID 与展示名）。 */
    private void persistMapping(String platform, String externalUserId, String tenantId,
                                UUID localUserId, String displayName) {
        var existing = externalUserMappingRepository
                .findByPlatformAndExternalUserIdAndTenantId(platform, externalUserId, tenantId);
        Instant now = Instant.now();
        if (existing.isPresent()) {
            var m = existing.get();
            m.setLocalUserId(localUserId);
            m.setDisplayName(displayName);
            m.setUpdatedAt(now);
            externalUserMappingRepository.save(m);
        } else {
            var m = new ExternalUserMappingEntity();
            m.setId(UUID.randomUUID());
            m.setPlatform(platform);
            m.setExternalUserId(externalUserId);
            m.setLocalUserId(localUserId);
            m.setTenantId(tenantId);
            m.setDisplayName(displayName);
            m.setMetadata("{}");
            m.setCreatedAt(now);
            m.setUpdatedAt(now);
            externalUserMappingRepository.save(m);
        }
    }

    /**
     * 小程序 code2Session：通过 js_code 换取 openid/session_key/unionid。
     *
     * <p>流程：
     * <ol>
     *   <li>调用 https://api.weixin.qq.com/sns/jscode2session</li>
     *   <li>返回 openid/session_key/unionid</li>
     *   <li>按 openid 在本地查找/创建用户</li>
     * </ol>
     */
    @Transactional
    public UserEntity loginFromMiniProgram(String jsCode) {
        if (!isConfigured()) {
            throw new IllegalStateException("微信小程序应用未配置");
        }

        String appId = getAppId();
        String appSecret = getAppSecret();

        // 1. 通过 js_code 获取 openid
        String sessionUrl = "https://api.weixin.qq.com/sns/jscode2session" +
                "?appid=" + appId + "&secret=" + appSecret + "&js_code=" + jsCode + "&grant_type=authorization_code";
        String sessionBody = restTemplate.getForObject(sessionUrl, String.class);
        JsonNode sessionJson = null;
        try {
            sessionJson = objectMapper.readTree(sessionBody == null ? "{}" : sessionBody);
        } catch (Exception e) {
            throw new RuntimeException("解析 session 失败", e);
        }
        String openid = sessionJson.path("openid").asText("");
        String unionId = sessionJson.path("unionid").asText("");
        if (openid.isBlank()) {
            String errMsg = sessionJson.path("errmsg").asText(sessionBody);
            throw new RuntimeException("获取 openid 失败：" + errMsg);
        }
        if (unionId.isBlank()) unionId = openid;

        // openid 是微信身份的唯一真相：优先走 ExternalUserMapping
        UserEntity bound = resolveByMapping("wechat", openid, "system");
        if (bound != null) {
            log.info("[MiniProgram] 映射已存在：openid={}", openid);
            return bound;
        }

        // 按 openid 哈希后的用户名查找本地用户（未建映射的旧数据兜底）
        String username = "wechat_" + safeTail(openid, 12);
        UserEntity existing = userRepository.findByUsername(username).orElse(null);
        if (existing != null) {
            persistMapping("wechat", openid, "system", existing.getId(), "微信用户");
            log.info("[MiniProgram] 用户已存在并补建映射：username={}, openid={}", username, openid);
            return existing;
        }

        // 创建本地用户（密码随机，不走密码登录）
        String randomPassword = UUID.randomUUID().toString();
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(randomPassword));
        user.setTenantId("system");
        user.setDisplayName("微信用户");
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
        persistMapping("wechat", openid, "system", user.getId(), "微信用户");
        log.info("[MiniProgram] 新建用户+映射：username={}, openid={}", username, openid);
        return user;
    }

    /** 取标识尾部并只保留字母数字，避免生成非法用户名。 */
    private static String safeTail(String raw, int max) {
        String s = raw == null ? "" : raw.replaceAll("[^a-zA-Z0-9]", "");
        if (s.isEmpty()) s = "unknown";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
