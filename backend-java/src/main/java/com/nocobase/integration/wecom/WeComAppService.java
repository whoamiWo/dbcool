package com.nocobase.integration.wecom;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.integration.credential.TenantCredentialService;
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
import java.util.Map;
import java.util.Optional;

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
    /** PHASE99 T1: 租户级凭证服务（优先于全局 @Value） */
    private final TenantCredentialService credentialService;
    /** A-2：外部建号统一路径 */
    private final com.nocobase.auth.UserProvisioningService provisioningService;
    /** E1：部门树写入 org_unit（外部来源只读镜像） */
    private final com.nocobase.org.OrgUnitService orgUnitService;

    // 以下 @Value 字段保留为**回退默认值**：租户级未配置时使用，存量部署零破坏
    @Value("${wecom.corp-id:}")
    private String corpId;

    @Value("${wecom.agent-id:}")
    private String agentId;

    @Value("${wecom.secret:}")
    private String secret;

    @Value("${wecom.redirect-uri:}")
    private String redirectUri;

    /** access_token 缓存（企微 token 有效期 7200s，缓存 10min 留 30s buffer） */
    private volatile String cachedToken;
    private volatile long cachedTokenExpireAt = 0;

    public WeComAppService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           RestTemplate restTemplate, TenantCredentialService credentialService,
                           com.nocobase.auth.UserProvisioningService provisioningService,
                           com.nocobase.org.OrgUnitService orgUnitService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.restTemplate = restTemplate;
        this.credentialService = credentialService;
        this.provisioningService = provisioningService;
        this.orgUnitService = orgUnitService;
    }

    /**
     * 按 key 解析凭证：租户级优先，回退全局 @Value。
     *
     * @param tenantId 租户 ID；传 null 时只取全局（用于无租户上下文的场景）
     */
    private Optional<String> resolveForKey(String key, String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            Optional<String> tenantVal = credentialService.get(tenantId, "wecom", key);
            if (tenantVal.isPresent()) {
                return tenantVal;
            }
        }
        // 回退全局 @Value
        String fallback = switch (key) {
            case "corp-id" -> corpId;
            case "agent-id" -> agentId;
            case "secret" -> secret;
            case "redirect-uri" -> redirectUri;
            default -> null;
        };
        return (fallback != null && !fallback.isBlank()) ? Optional.of(fallback) : Optional.empty();
    }

    public String getCorpId() {
        return resolveForKey("corp-id", null).orElse("");
    }

    public String getAgentId() {
        return resolveForKey("agent-id", null).orElse("");
    }

    public String getSecret() {
        return resolveForKey("secret", null).orElse("");
    }

    /** 是否已配置企业微信应用凭证。 */
    public boolean isConfigured() {
        return resolveForKey("corp-id", null).isPresent()
                && resolveForKey("agent-id", null).isPresent()
                && resolveForKey("secret", null).isPresent()
                && resolveForKey("redirect-uri", null).isPresent();
    }

    /**
     * 获取企业微信 access_token（调用 /cgi-bin/gettoken），带本地缓存。
     * <p>企微 token 有效期 7200s，缓存 10min 留 30s buffer，避免高频调用触发频率限制。</p>
     */
    public String getAccessToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < cachedTokenExpireAt) {
            return cachedToken;
        }
        String corpId = resolveForKey("corp-id", null).orElse("");
        String secret = resolveForKey("secret", null).orElse("");
        if (corpId.isBlank() || secret.isBlank()) {
            throw new IllegalStateException("企业微信 corp-id 或 secret 未配置");
        }
        String url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken" +
                "?corpid=" + corpId + "&corpsecret=" + secret;
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
        final String tenantId = "system";
        UserEntity existing = userRepository.findByUsername(username).orElse(null);
        if (existing != null) {
            // 归属校验：企业微信账号固定归属 system 租户；命中其他租户同名账号
            // 说明用户名哈希碰撞或数据被篡改，必须拒绝而不是跨租户复用。
            if (!existing.getTenantId().equals(tenantId)) {
                throw new IllegalStateException("企业微信账号存在跨租户冲突: " + username);
            }
            log.info("[WeCom] 用户已存在: username={}", username);
            return existing;
        }

        // A-2：统一建号路径 —— 企业微信托管账号无本地密码（此前写 bcrypt(random)）
        UserEntity user = provisioningService.createExternal(
                tenantId, username, name, email, "WECOM");
        log.info("[WeCom] 新建用户: username={}, unionId={}", username, unionId);
        return user;
    }

    /**
     * 同步企业微信通讯录：部门 → 成员。
     *
     * <p>1. 调用 /cgi-bin/department/list 获取部门树
     * 2. 调用 /cgi-bin/user/simplelist 获取成员（按部门迭代）
     * 3. 写入本地用户表（已存在则跳过）
     *
     * @return 同步结果摘要
     */
    @Transactional
    public Map<String, Object> syncContacts() {
        if (!isConfigured()) {
            throw new IllegalStateException("企业微信应用未配置");
        }
        String accessToken = getAccessToken();

        // 1. 拉取部门列表
        String deptUrl = "https://qyapi.weixin.qq.com/cgi-bin/department/list" +
                "?access_token=" + accessToken;
        String deptBody = restTemplate.getForObject(deptUrl, String.class);
        JsonNode deptJson = null;
        try {
            deptJson = objectMapper.readTree(deptBody == null ? "{}" : deptBody);
        } catch (Exception e) {
            throw new RuntimeException("解析部门列表失败", e);
        }
        int deptCount = 0;
        java.util.Map<String, java.util.UUID> localDeptIds = new java.util.HashMap<>();
        if (deptJson.has("department") && deptJson.get("department").isArray()) {
            deptCount = deptJson.get("department").size();
            // E1：按 parentid 顺序写 org_unit（企微返回列表通常父在子前；
            // 父未落库时先按根处理，稍后由 path 修复）
            for (JsonNode dept : deptJson.get("department")) {
                String extId = dept.path("id").asText("");
                if (extId.isBlank()) continue;
                String parentExt = dept.path("parentid").asText("");
                try {
                    var unit = orgUnitService.upsertExternal(
                            "system", "WECOM", extId, dept.path("name").asText(extId),
                            parentExt.isBlank() || "0".equals(parentExt) ? null : parentExt,
                            dept.path("order").asInt(0));
                    localDeptIds.put(extId, unit.getId());
                } catch (Exception e) {
                    log.warn("[WeCom] 部门 {} 写入 org_unit 失败: {}", extId, e.getMessage());
                }
            }
        }

        // 2. 拉取成员列表（简单场景：按根部门 / 迭代拉取）
        int userCount = 0;
        if (deptJson.has("department") && deptJson.get("department").size() > 0) {
            String rootDeptId = deptJson.get("department").get(0).path("id").asText("1");
            String userListUrl = "https://qyapi.weixin.qq.com/cgi-bin/user/simplelist" +
                    "?access_token=" + accessToken + "&department_id=" + rootDeptId + "&fetch_child=1";
            String userListBody = restTemplate.getForObject(userListUrl, String.class);
            JsonNode userListJson = null;
            try {
                userListJson = objectMapper.readTree(userListBody == null ? "{}" : userListBody);
            } catch (Exception e) {
                throw new RuntimeException("解析成员列表失败", e);
            }
            if (userListJson.has("userlist") && userListJson.get("userlist").isArray()) {
                for (JsonNode u : userListJson.get("userlist")) {
                    String uid = u.path("userid").asText("");
                    String uname = u.path("name").asText(uid);
                    String uemail = u.path("email").asText("");
                    if (uid.isBlank()) continue;
                    String uusername = "wecom_" + safeTail(uid, 12);
                    if (userRepository.findByUsername(uusername).isEmpty() &&
                            userRepository.findByEmail(uemail).isEmpty()) {
                        // A-2：统一建号路径
                        var created = provisioningService.createExternal(
                                "system", uusername, uname, uemail, "WECOM");
                        // E1：写入部门归属（simplelist 的 department 数组首个 id）
                        JsonNode deptArr = u.path("department");
                        if (created != null && deptArr.isArray() && !deptArr.isEmpty()) {
                            java.util.UUID local = localDeptIds.get(deptArr.get(0).asText(""));
                            if (local != null) {
                                created.setDepartmentId(local);
                                userRepository.save(created);
                            }
                        }
                        userCount++;
                    }
                }
            }
        }

        log.info("[WeCom] 通讯录同步完成: departments={} users={}", deptCount, userCount);
        return Map.of("code", 0, "message", "sync completed",
                "departments", deptCount, "users", userCount);
    }

    /** 取标识尾部并只保留字母数字，避免生成非法用户名。 */
    private static String safeTail(String raw, int max) {
        String s = raw == null ? "" : raw.replaceAll("[^a-zA-Z0-9]", "");
        if (s.isEmpty()) s = "unknown";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}