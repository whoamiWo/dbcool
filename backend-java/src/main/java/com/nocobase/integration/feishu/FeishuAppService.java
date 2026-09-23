package com.nocobase.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * 飞书应用服务：OAuth2、事件处理、配置管理。
 */
@Service
public class FeishuAppService {
    private static final Logger log = LoggerFactory.getLogger(FeishuAppService.class);

    @Value("${feishu.app-id:}")
    private String appId;

    @Value("${feishu.app-secret:}")
    private String appSecret;

    @Value("${feishu.redirect-uri:}")
    private String redirectUri;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BASE_URL = "https://open.feishu.cn/open-apis";

    public String getOAuthUrl() {
        if (appId == null || appId.isBlank()) {
            throw new IllegalStateException("飞书 app-id 未配置");
        }
        String url = BASE_URL + "/auth/v3/auth/url?app_id=" + appId +
                "&redirect_uri=" + redirectUri +
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
        return appId != null && !appId.isBlank() &&
               appSecret != null && !appSecret.isBlank();
    }

    /**
     * 飞书签名校验：SHA256(timestamp + nonce + encrypt_key + body)。
     *
     * <p>飞书文档约定：服务端用同样的算法计算签名，与请求头
     * {@code X-Lark-Signature} 比对，验证请求来源合法性。
     */
    public boolean verifySignature(String timestamp, String nonce, String signature, String body) {
        if (timestamp == null || nonce == null || signature == null) {
            return false;
        }
        // 飞书签名算法：SHA256(timestamp + nonce + encrypt_key + body)
        String encryptKey = ""; // 加密 key（如果启用加密模式，需配置）
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

        return body.path("tenant_access_token").asText();
    }
}