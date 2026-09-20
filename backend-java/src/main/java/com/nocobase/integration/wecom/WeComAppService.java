package com.nocobase.integration.wecom;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 企业微信应用服务 — 授权、code 交换与用户同步。
 *
 * <p>参考 DingTalkAppService 实现：
 * <ul>
 *   <li>已存在 → 直接复用(幂等,重复登录不会建重复账号)</li>
 *   <li>不存在 → 以 {@code wecom_<unionid 摘要>} 为用户名建号,密码随机(不走密码登录)</li>
 * </ul>
 */
@Service
public class WeComAppService {

    private static final Logger log = LoggerFactory.getLogger(WeComAppService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${wecom.corp-id:}")
    private String corpId;

    @Value("${wecom.agent-id:}")
    private String agentId;

    @Value("${wecom.secret:}")
    private String secret;

    @Value("${wecom.redirect-uri:}")
    private String redirectUri;

    public WeComAppService(UserRepository userRepository, PasswordEncoder passwordEncoder, RestTemplate restTemplate) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.restTemplate = restTemplate;
    }

    public String getCorpId() { return corpId; }
    public String getAgentId() { return agentId; }
    public String getSecret() { return secret; }

    /** 是否已配置企业微信应用凭证。 */
    public boolean isConfigured() {
        return corpId != null && !corpId.isBlank()
                && agentId != null && !agentId.isBlank()
                && secret != null && !secret.isBlank()
                && redirectUri != null && !redirectUri.isBlank();
    }

    /**
     * 获取企业微信 access_token（调用 /cgi-bin/gettoken）。
     * <p>生产环境应使用缓存（如 Redis），此处为演示直接请求。</p>
     */
    public String getAccessToken() {
        String url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken" +
                "?corpid=" + corpId + "&corpsecret=" + secret;
        String body = restTemplate.getForObject(url, String.class);
        try {
            JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
            String token = json.path("access_token").asText("");
            if (token.isBlank()) {
                throw new RuntimeException("获取 access_token 失败：" + body);
            }
            return token;
        } catch (Exception e) {
            throw new RuntimeException("解析 access_token 失败", e);
        }
    }

    /**
     * 通过 code 换取用户信息并同步到本地。
     *
     * <p>流程：
     * <ol>
     *   <li>用 code 调用 /cgi-bin/user/getuserinfo 获取 userid</li>
     *   <li>再调用 /cgi-bin/user/get 获取详细信息（name, email, avatar, mobile, unionid）</li>
     *   <li>按 unionid（或 userid）在本地查找/创建用户</li>
     *   <li>签发 JWT 与 refresh_token，返回给前端</li>
     * </ol>
     */
    @Transactional
    public UserEntity loginFromWeCom(String code) {
        if (!isConfigured()) {
            throw new IllegalStateException("企业微信应用未配置");
        }

        String accessToken = getAccessToken();

        // 1. 通过 code 获取用户信息
        String userUrl = "https://qyapi.weixin.qq.com/cgi-bin/user/getuserinfo" +
                "?access_token=" + accessToken + "&code=" + code;
        String userBody = restTemplate.getForObject(userUrl, String.class);
        JsonNode userJson = null;
        try {
            userJson = objectMapper.readTree(userBody == null ? "{}" : userBody);
        } catch (Exception e) {
            throw new RuntimeException("解析用户信息失败", e);
        }
        String userId = userJson.path("userid").asText("");
        if (userId.isBlank()) {
            throw new RuntimeException("获取 userid 失败：" + userBody);
        }

        // 2. 获取详细信息
        String detailUrl = "https://qyapi.weixin.qq.com/cgi-bin/user/get" +
                "?access_token=" + accessToken + "&userid=" + userId;
        String detailBody = restTemplate.getForObject(detailUrl, String.class);
        JsonNode detailJson = null;
        try {
            detailJson = objectMapper.readTree(detailBody == null ? "{}" : detailBody);
        } catch (Exception e) {
            throw new RuntimeException("解析用户详情失败", e);
        }
        String name = detailJson.path("name").asText(userId);
        String email = detailJson.path("email").asText("");
        String avatar = detailJson.path("avatar").asText("");
        String mobile = detailJson.path("mobile").asText("");
        String unionId = detailJson.path("unionid").asText("");
        if (unionId.isBlank()) unionId = userId;

        // 按 unionId 哈希后的用户名查找本地用户
        String username = "wecom_" + safeTail(unionId, 12);
        UserEntity existing = userRepository.findByUsername(username).orElse(null);
        if (existing != null) {
            log.info("[WeCom] 用户已存在: username={}", username);
            return existing;
        }

        // 创建本地用户（密码随机，不走密码登录；与钉钉 dt_ 前缀模式对称）
        String randomPassword = UUID.randomUUID().toString();
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(randomPassword));
        user.setTenantId("system");
        user.setDisplayName(name);
        user.setEmail(email);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
        log.info("[WeCom] 新建用户: username={}, unionId={}", username, unionId);
        return user;
    }

    /** 取标识尾部并只保留字母数字，避免生成非法用户名。 */
    private static String safeTail(String raw, int max) {
        String s = raw == null ? "" : raw.replaceAll("[^a-zA-Z0-9]", "");
        if (s.isEmpty()) s = "unknown";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}