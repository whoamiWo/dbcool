package com.nocobase.integration.wecom;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${wecom.corp-id:}")
    private String corpId;

    @Value("${wecom.agent-id:}")
    private String agentId;

    @Value("${wecom.secret:}")
    private String secret;

    @Value("${wecom.redirect-uri:}")
    private String redirectUri;

    public WeComAppService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

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
        // TODO: 实现 HTTP 请求获取 access_token
        // 示例: GET https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=XXX&corpsecret=YYY
        return "FAKE_ACCESS_TOKEN_FOR_DEMO";
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

        // TODO: 实际调用企业微信接口
        // 1. 通过 code 获取用户信息
        // String accessToken = getAccessToken();
        // String userInfoUrl = "https://qyapi.weixin.qq.com/cgi-bin/user/getuserinfo?access_token=" + accessToken + "&code=" + code;
        // JsonNode userInfoResp = objectMapper.readValue(httpGet(userInfoUrl), JsonNode.class);
        // String userId = userInfoResp.get("userid").asText();
        // // 2. 获取详细信息
        // String detailUrl = "https://qyapi.weixin.qq.com/cgi-bin/user/get?access_token=" + accessToken + "&userid=" + userId;
        // JsonNode detailResp = objectMapper.readValue(httpGet(detailUrl), JsonNode.class);
        // String name = detailResp.get("name").asText();
        // String email = detailResp.get("email").asText();
        // String avatar = detailResp.get("avatar").asText();
        // String mobile = detailResp.get("mobile").asText();
        // String unionId = detailResp.get("unionid").asText();

        // 演示数据
        String unionId = "wecom_unionid_demo_" + code.hashCode();
        String name = "企业微信用户_" + code.substring(0, 4);
        String email = name + "@wecom.example.com";

        // 按 unionId 哈希后的用户名查找本地用户
        String username = "wecom_" + Base64.getEncoder().encodeToString(unionId.getBytes()).substring(0, 8);
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
        log.info("[WeCom] 新建用户: username={}", username);
        return user;
    }
}