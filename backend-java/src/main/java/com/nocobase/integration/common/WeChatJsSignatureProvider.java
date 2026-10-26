package com.nocobase.integration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.credential.TenantCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * 微信公众号 JS-SDK 签名实现 — provider="wechat"。
 *
 * <p>流程：
 * <ol>
 *   <li>获取 access_token（调用 /cgi-bin/token）</li>
 *   <li>用 access_token 获取 jsapi_ticket（调用 /cgi-bin/ticket/getticket?type=jsapi）</li>
 *   <li>组合参数（jsapi_ticket, nonceStr, timestamp, url）进行 SHA1 签名</li>
 * </ol>
 *
 * <p>复用：
 * <ul>
 *   <li>{@link JsSignatureUtil#sha1Sign(String, String, long, String)}（与微信官方算法一致）</li>
 *   <li>{@link JsTicketCache}（Redis，key: js:ticket:wechat:{tenantId}:default）</li>
 *   <li>{@link TenantCredentialService}（凭证解析）</li>
 * </ul>
 */
@Component
public class WeChatJsSignatureProvider implements JsSignatureProvider {

    private static final Logger log = LoggerFactory.getLogger(WeChatJsSignatureProvider.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenantCredentialService credentialService;
    private final JsTicketCache ticketCache;

    public WeChatJsSignatureProvider(RestTemplate restTemplate,
                                     TenantCredentialService credentialService,
                                     JsTicketCache ticketCache) {
        this.restTemplate = restTemplate;
        this.credentialService = credentialService;
        this.ticketCache = ticketCache;
    }

    @Override
    public String provider() {
        return "wechat";
    }

    @Override
    public JsSignature sign(String tenantId, String url, String type) {
        String appId = resolveCredential(tenantId, "app-id");
        String appSecret = resolveCredential(tenantId, "app-secret");
        if (appId.isBlank() || appSecret.isBlank()) {
            throw new IllegalStateException("微信 app-id 或 app-secret 未配置");
        }

        // 1. 获取 jsapi_ticket（走缓存）
        String ticket = ticketCache.get(tenantId, "wechat", "default").orElseGet(() -> {
            String accessToken = getAccessToken(appId, appSecret);
            String newTicket = getJsapiTicket(accessToken);
            ticketCache.put(tenantId, "wechat", "default", newTicket);
            return newTicket;
        });

        // 2. 构造签名参数
        String nonceStr = JsSignatureUtil.nonceStr();
        long timestamp = JsSignatureUtil.currentTimestampSeconds();
        String signature = JsSignatureUtil.sha1Sign(ticket, nonceStr, timestamp, url);

        log.debug("[WeChatJsSignature] sign: tenantId={}, url={}, ticket={}", tenantId, url, ticket);
        return new JsSignature(appId, nonceStr, timestamp, url, signature, "default");
    }

    /** 按 key 解析凭证：租户级优先，回退全局空值 */
    private String resolveCredential(String tenantId, String key) {
        if (tenantId != null && !tenantId.isBlank()) {
            Optional<String> val = credentialService.get(tenantId, "wechat", key);
            if (val.isPresent()) return val.get();
        }
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

    /** 获取 jsapi_ticket */
    private String getJsapiTicket(String accessToken) {
        String url = "https://api.weixin.qq.com/cgi-bin/ticket/getticket" +
                "?access_token=" + accessToken + "&type=jsapi";
        String body = restTemplate.getForObject(url, String.class);
        try {
            JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
            String ticket = json.path("ticket").asText("");
            int errcode = json.path("errcode").asInt(-1);
            if (ticket.isBlank() || errcode != 0) {
                String errmsg = json.path("errmsg").asText(body);
                throw new RuntimeException("获取 jsapi_ticket 失败：" + errmsg);
            }
            // 微信官方 ticket 有效期 7200s，缓存 TTL 取 7000s 提前刷新（由 JsTicketCache 控制）
            return ticket;
        } catch (Exception e) {
            throw new RuntimeException("解析 jsapi_ticket 失败", e);
        }
    }
}
