package com.nocobase.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.credential.TenantCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * 微信订阅消息分发器 — 公众号/小程序统一订阅消息下发端点。
 *
 * <p>实现：
 * <ul>
 *   <li>复用 WeChatAppService.getAccessToken() 获取 access_token</li>
 *   <li>调用 https://api.weixin.qq.com/cgi-bin/message/subscribe/send</li>
 * </ul>
 */
@Component
public class WeChatSubscribeDispatcher implements NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WeChatSubscribeDispatcher.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenantCredentialService credentialService;

    public WeChatSubscribeDispatcher(RestTemplate restTemplate,
                                     TenantCredentialService credentialService) {
        this.restTemplate = restTemplate;
        this.credentialService = credentialService;
    }

    @Override
    public NotificationChannelEntity.Type supportedType() {
        return NotificationChannelEntity.Type.WECHAT_PERSONAL;
    }

    /**
     * 发送微信订阅消息。
     *
     * @param channel 渠道配置（config 包含 app-id, app-secret）
     * @param recipient openid（订阅消息接收者）
     * @param payload 订阅消息内容，包含：
     *                <ul>
     *                  <li>template_id: 模板 ID</li>
     *                  <li>page: 点击卡片跳转页面路径</li>
     *                  <li>data: 字段值对象，如 { thing1: {value: "xxx"}, date2: {value: "yyy"} }</li>
     *                </ul>
     * @return 发送结果
     */
    @Override
    public SendResult send(NotificationChannelEntity channel, String recipient, Map<String, Object> payload) {
        try {
            // 1. 获取 access_token
            String appId = resolveCredential(channel.getTenantId(), "app-id");
            String appSecret = resolveCredential(channel.getTenantId(), "app-secret");
            if (appId.isBlank() || appSecret.isBlank()) {
                return SendResult.error("微信 app-id 或 app-secret 未配置");
            }
            String accessToken = getAccessToken(appId, appSecret);

            // 2. 构建订阅消息请求体
            // POST https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=TOKEN
            // body: { touser: OPENID, template_id: TEMPLATE_ID, page: PAGE_PATH, data: { key: {value: VALUE} } }
            String url = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send" +
                    "?access_token=" + accessToken;

            JsonNode payloadJson = objectMapper.valueToTree(payload);
            String body = restTemplate.postForObject(url, payloadJson.toString(), String.class);

            JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
            int errcode = json.path("errcode").asInt(-1);
            if (errcode != 0) {
                String errmsg = json.path("errmsg").asText(body);
                log.warn("[WeChatSubscribe] 发送失败：{}", errmsg);
                return SendResult.error("发送失败：" + errmsg);
            }

            log.info("[WeChatSubscribe] 发送成功：openid={}, template_id={}",
                    recipient, payload.get("template_id"));
            return SendResult.ok("发送成功");
        } catch (Exception e) {
            log.error("[WeChatSubscribe] 发送异常", e);
            return SendResult.error("发送异常：" + e.getMessage());
        }
    }

    /** 按 key 解析凭证：租户级优先，回退全局空值 */
    private String resolveCredential(String tenantId, String key) {
        if (tenantId != null && !tenantId.isBlank()) {
            Optional<String> val = credentialService.get(tenantId, "wechat", key);
            if (val.isPresent()) return val.get();
        }
        // 回退全局（此处简化为返回空，实际可扩展为 @Value 注入）
        return "";
    }

    /** 获取微信公众号 access_token（带简单缓存） */
    private volatile String cachedToken;
    private volatile long cachedTokenExpireAt = 0;

    private String getAccessToken(String appId, String appSecret) {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < cachedTokenExpireAt) {
            return cachedToken;
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
}
