package com.nocobase.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.credential.TenantCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.HexFormat;

/**
 * 飞书应用服务：OAuth2、事件处理、配置管理。
 */
@Service
public class FeishuAppService {
    private static final Logger log = LoggerFactory.getLogger(FeishuAppService.class);

    /** PHASE99 T1: 租户级凭证服务（优先于全局 @Value） */
    private final TenantCredentialService credentialService;

    // 以下 @Value 字段保留为**回退默认值**：租户级未配置时使用，存量部署零破坏
    @Value("${feishu.app-id:}")
    private String appId;

    @Value("${feishu.app-secret:}")
    private String appSecret;

    @Value("${feishu.redirect-uri:}")
    private String redirectUri;

    @Value("${feishu.encrypt-key:}")
    private String encryptKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 租户级 tenant_access_token 缓存。
     *
     * <p>key = tenantId（null 用空串）。缓存按租户隔离，禁止跨租户复用 token：
     * 不同租户的 app_id/app_secret 不同，复用会串号。
     */
    private final Map<String, CachedToken> tokenCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long TOKEN_REFRESH_SKEW_MS = 5 * 60 * 1000L;

    private record CachedToken(String token, long expiresAt) {}

    private static final String BASE_URL = "https://open.feishu.cn/open-apis";

    public FeishuAppService(TenantCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /**
     * 按 key 解析凭证：租户级优先，回退全局 @Value。
     *
     * @param tenantId 租户 ID；传 null 时只取全局（用于无租户上下文的场景）
     */
    private Optional<String> resolveForKey(String key, String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            Optional<String> tenantVal = credentialService.get(tenantId, "feishu", key);
            if (tenantVal.isPresent()) {
                return tenantVal;
            }
        }
        // 回退全局 @Value
        String fallback = switch (key) {
            case "app-id" -> appId;
            case "app-secret" -> appSecret;
            case "redirect-uri" -> redirectUri;
            case "encrypt-key" -> encryptKey;
            default -> null;
        };
        return (fallback != null && !fallback.isBlank()) ? Optional.of(fallback) : Optional.empty();
    }

    /** 兼容旧签名：无租户上下文时只取全局配置 */
    public String getAppId() {
        return resolveForKey("app-id", null).orElse("");
    }

    public String getAppSecret() {
        return resolveForKey("app-secret", null).orElse("");
    }

    public String getEncryptKey() {
        return resolveForKey("encrypt-key", null).orElse("");
    }

    public String getOAuthUrl() {
        String appId = resolveForKey("app-id", null).orElse("");
        if (appId.isBlank()) {
            throw new IllegalStateException("飞书 app-id 未配置");
        }
        String redirect = resolveForKey("redirect-uri", null).orElse("");
        String url = BASE_URL + "/auth/v3/auth/url?app_id=" + appId +
                "&redirect_uri=" + redirect +
                "&scope=im:message:send_as_bot,im:message:send_internal";
        return url;
    }

    public Map<String, Object> handleOAuthCallback(String code) throws Exception {
        // 调用飞书 API 获取 tenant_access_token
        Map<String, Object> payload = new HashMap<>();
        payload.put("app_id", appId);
        payload.put("app_secret", appSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                BASE_URL + "/auth/v3/tenant_access_token/internal",
                entity,
                JsonNode.class
        );

        JsonNode body = resp.getBody();
        if (body == null || body.path("tenant_access_token").isMissingNode()) {
            throw new RuntimeException("获取 tenant_access_token 失败：" + (body != null ? body.toString() : "无响应"));
        }

        return Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "tenant_access_token", body.path("tenant_access_token").asText(),
                        "expire_in", body.path("expire_in").asInt()
                )
        );
    }

    public void handleEvent(JsonNode event) {
        String type = event.path("type").asText();
        String subtype = event.path("subtype").asText("");

        // 处理 message 事件
        if ("message".equals(type)) {
            String text = event.path("event").path("message").path("content").path("text").asText("");
            String openId = event.path("event").path("message").path("open_id").asText("");
            String messageId = event.path("event").path("message").path("message_id").asText("");

            // 事件记录到日志，便于运营审计与 AI Copilot 异步消费
            log.info("[Feishu] 入站消息: openId={}, messageId={}, text={}", openId, messageId, text);
        }

        log.debug("[Feishu] 事件类型: type={}, subtype={}", type, subtype);
    }

    public boolean isConfigured() {
        return isConfigured(null);
    }

    /** 租户级配置判定：租户凭证优先，缺失时回退全局 @Value。 */
    public boolean isConfigured(String tenantId) {
        return resolveForKey("app-id", tenantId).isPresent()
                && resolveForKey("app-secret", tenantId).isPresent();
    }

    /**
     * 飞书签名校验：SHA256(timestamp + nonce + encrypt_key + body)。
     *
     * <p>飞书文档约定：服务端用同样的算法计算签名，与请求头
     * {@code X-Lark-Signature} 比对，验证请求来源合法性。
     */
    public boolean verifySignature(String timestamp, String nonce, String signature, String body) {
        if (timestamp == null || nonce == null || signature == null) {
            log.warn("[Feishu] 签名校验：缺少必填参数(timestamp/nonce/signature)");
            return false;
        }
        String encryptKey = resolveForKey("encrypt-key", null).orElse("");
        if (encryptKey.isBlank()) {
            log.warn("[Feishu] 签名校验：encrypt-key 未配置，拒绝请求");
            return false;
        }
        String signingString = timestamp + nonce + encryptKey + body;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signingString.getBytes(StandardCharsets.UTF_8));
            String expectedSig = HexFormat.of().formatHex(hash);
            boolean match = signature.equals(expectedSig);
            if (!match) {
                log.warn("[Feishu] 签名校验失败: expected={}, got={}", expectedSig, signature);
            }
            return match;
        } catch (NoSuchAlgorithmException e) {
            log.error("[Feishu] SHA-256 算法不可用", e);
            return false;
        }
    }

    public Map<String, Object> sendMessage(String openId, String text) throws Exception {
        String accessToken = getTenantAccessToken();
        // 使用 JsonNode 构造 content，避免字符串拼接导致的 JSON 注入风险
        ObjectMapper mapper = new ObjectMapper();
        JsonNode contentNode = mapper.createObjectNode().put("text", text);

        Map<String, Object> payload = new HashMap<>();
        payload.put("receive_id", openId);
        payload.put("msg_type", "text");
        payload.put("content", contentNode);
        payload.put("receive_id_type", "open_id");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + accessToken);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                BASE_URL + "/im/v1/messages",
                entity,
                JsonNode.class
        );

        JsonNode body = resp.getBody();
        if (body == null || body.path("code").asInt() != 0) {
            throw new RuntimeException("飞书发送消息失败：" + (body != null ? body.toString() : "无响应"));
        }

        return Map.of(
                "code", 0,
                "message", "sent",
                "data", Map.of(
                        "message_id", body.path("data").path("message_id").asText("")
                )
        );
    }

    public Map<String, Object> replyMessage(String openId, String replyToMsgId, String text) throws Exception {
        String accessToken = getTenantAccessToken();
        // 使用 JsonNode 构造 content，避免字符串拼接导致的 JSON 注入风险
        ObjectMapper mapper = new ObjectMapper();
        JsonNode contentNode = mapper.createObjectNode().put("text", text);

        Map<String, Object> payload = new HashMap<>();
        payload.put("receive_id", openId);
        payload.put("msg_type", "text");
        payload.put("content", contentNode);
        payload.put("receive_id_type", "open_id");
        payload.put("reply_to_message_id", replyToMsgId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + accessToken);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                BASE_URL + "/im/v1/messages",
                entity,
                JsonNode.class
        );

        JsonNode body = resp.getBody();
        if (body == null || body.path("code").asInt() != 0) {
            throw new RuntimeException("飞书回复消息失败：" + (body != null ? body.toString() : "无响应"));
        }

        return Map.of(
                "code", 0,
                "message", "sent",
                "data", Map.of(
                        "message_id", body.path("data").path("message_id").asText("")
                )
        );
    }

    private String getTenantAccessToken() throws Exception {
        return getTenantAccessToken(null);
    }

    /**
     * 获取租户级 {@code tenant_access_token}（企业内部应用）。
     *
     * <p>飞书返回的 {@code expire} 为秒；缓存提前 5 分钟过期，避免临界点请求失败。
     * 缓存 key 含 tenantId —— 每个租户用自己的 app_id/app_secret 换取 token，
     * 绝不能回退到全局凭证跨租户使用。
     */
    public String getTenantAccessToken(String tenantId) throws Exception {
        String cacheKey = tenantId == null ? "" : tenantId;
        long now = System.currentTimeMillis();
        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAt() > now) {
            return cached.token();
        }

        String key = resolveForKey("app-id", tenantId).orElse("");
        String secret = resolveForKey("app-secret", tenantId).orElse("");
        if (key.isBlank() || secret.isBlank()) {
            throw new IllegalStateException("飞书应用未配置 (feishu.app-id / app-secret)");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("app_id", key);
        payload.put("app_secret", secret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                BASE_URL + "/auth/v3/tenant_access_token/internal",
                entity,
                JsonNode.class
        );

        JsonNode body = resp.getBody();
        int code = body == null ? -1 : body.path("code").asInt(0);
        if (body == null || code != 0 || body.path("tenant_access_token").asText("").isBlank()) {
            throw new RuntimeException("获取 tenant_access_token 失败："
                    + (body != null ? body.toString() : "无响应"));
        }

        String token = body.path("tenant_access_token").asText();
        int expire = body.path("expire").asInt(body.path("expire_in").asInt(7200));
        long ttl = Math.max(60_000L, expire * 1000L - TOKEN_REFRESH_SKEW_MS);
        tokenCache.put(cacheKey, new CachedToken(token, now + ttl));
        return token;
    }

    /**
     * 递归收集全部部门（BFS，父在子前）。
     *
     * <p>飞书 {@code GET /contact/v3/departments/{id}/children} 只返回直接子部门，
     * 因此从根 {@code 0} 开始逐层展开，并对每层做 page_token 分页。
     */
    public List<Map<String, Object>> listDepartments(String tenantId) throws Exception {
        String token = getTenantAccessToken(tenantId);
        List<Map<String, Object>> all = new ArrayList<>();
        java.util.Set<String> visited = new java.util.LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add("0");
        int maxNodes = 2000;

        while (!queue.isEmpty() && all.size() < maxNodes) {
            String parentDeptId = queue.poll();
            String pageToken = "";
            int pages = 0;
            while (pages++ < 500) {
                String url = BASE_URL + "/contact/v3/departments/"
                        + parentDeptId + "/children?page_size=50"
                        + "&department_id_type=open_department_id"
                        + (pageToken.isBlank() ? "" : "&page_token=" + encode(pageToken));
                JsonNode data = contactGet(token, url, "部门列表");
                JsonNode items = data.path("items");
                if (items.isArray()) {
                    for (JsonNode dept : items) {
                        String id = dept.path("open_department_id").asText(
                                dept.path("department_id").asText(""));
                        if (id.isBlank() || !visited.add(id)) continue;
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", id);
                        m.put("name", dept.path("name").asText(""));
                        String parent = dept.path("parent_department_id").asText("");
                        m.put("parentId", parent.isBlank() ? null : parent);
                        m.put("order", dept.path("order").asInt(0));
                        all.add(m);
                        queue.add(id);
                    }
                }
                String next = data.path("page_token").asText("");
                boolean hasMore = data.path("has_more").asBoolean(false);
                if (!hasMore || next.isBlank() || next.equals(pageToken)) break;
                pageToken = next;
            }
        }
        return all;
    }

    /** 拉取某部门的直接成员（page_token 分页全量）。 */
    public List<Map<String, Object>> listDepartmentUsers(String tenantId, String deptId) throws Exception {
        String token = getTenantAccessToken(tenantId);
        List<Map<String, Object>> users = new ArrayList<>();
        String pageToken = "";
        int pages = 0;
        while (pages++ < 500) {
            String url = BASE_URL + "/contact/v3/users?department_id=" + encode(deptId)
                    + "&page_size=50&user_id_type=open_id"
                    + "&department_id_type=open_department_id"
                    + (pageToken.isBlank() ? "" : "&page_token=" + encode(pageToken));
            JsonNode data = contactGet(token, url, "部门成员");
            JsonNode items = data.path("items");
            if (items.isArray()) {
                for (JsonNode user : items) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("openId", user.path("open_id").asText(""));
                    m.put("name", user.path("name").asText(""));
                    m.put("email", user.path("email").asText(""));
                    m.put("mobile", user.path("mobile").asText(""));
                    m.put("jobTitle", user.path("job_title").asText(""));
                    m.put("leaderUserId", user.path("leader_user_id").asText(""));
                    List<String> deptIds = new ArrayList<>();
                    JsonNode depts = user.path("department_ids");
                    if (depts.isArray()) {
                        for (JsonNode d : depts) deptIds.add(d.asText(""));
                    }
                    m.put("departmentIds", deptIds);
                    users.add(m);
                }
            }
            String next = data.path("page_token").asText("");
            boolean hasMore = data.path("has_more").asBoolean(false);
            if (!hasMore || next.isBlank() || next.equals(pageToken)) break;
            pageToken = next;
        }
        return users;
    }

    private JsonNode contactGet(String token, String url, String what) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        ResponseEntity<JsonNode> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        JsonNode body = resp.getBody();
        if (body == null) {
            throw new IllegalStateException(what + "失败：无响应");
        }
        int code = body.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException(what + "失败：code=" + code
                    + " msg=" + body.path("msg").asText("unknown"));
        }
        return body.path("data");
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}