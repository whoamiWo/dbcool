package com.nocobase.integration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.credential.TenantCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 钉钉 JS-SDK 签名。
 *
 * <p>ticket 端点：{@code GET https://oapi.dingtalk.com/get_jsapi_ticket?access_token=xxx}
 * 返回 {@code ticket}（有效期 7200s）。
 *
 * <p>凭证：{@code credentialService.get(tenantId, "dingtalk", "app-key" / "app-secret")}。
 */
@Component
public class DingTalkJsSignatureProvider implements JsSignatureProvider {

    private static final Logger log = LoggerFactory.getLogger(DingTalkJsSignatureProvider.class);

    private static final String TOKEN_URL = "https://oapi.dingtalk.com/gettoken";
    private static final String TICKET_URL = "https://oapi.dingtalk.com/get_jsapi_ticket";

    private final TenantCredentialService credentialService;
    private final JsTicketCache ticketCache;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DingTalkJsSignatureProvider(TenantCredentialService credentialService,
                                       JsTicketCache ticketCache,
                                       RestTemplate restTemplate,
                                       ObjectMapper objectMapper) {
        this.credentialService = credentialService;
        this.ticketCache = ticketCache;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String provider() {
        return "dingtalk";
    }

    /** 钉钉只有单一 jsapi_ticket，type 参数忽略，固定返回 default。 */
    @Override
    public JsSignature sign(String tenantId, String url, String type) {
        String appKey = credentialService.get(tenantId, "dingtalk", "app-key")
                .orElseThrow(() -> new IllegalStateException(
                        "钉钉 app-key 未配置（租户=" + tenantId + "），无法生成 JS-SDK 签名"));
        String ticket = ticket(tenantId);
        String nonce = JsSignatureUtil.nonceStr();
        long ts = JsSignatureUtil.currentTimestampSeconds();
        return new JsSignature(appKey, nonce, ts, url,
                JsSignatureUtil.sha1Sign(ticket, nonce, ts, url), "default");
    }

    private String ticket(String tenantId) {
        return ticketCache.get(tenantId, "dingtalk", "default").orElseGet(() -> {
            String token = accessToken(tenantId);
            String body = restTemplate.getForObject(TICKET_URL + "?access_token=" + token, String.class);
            JsonNode json = readTree(body);
            String ticket = json.path("ticket").asText("");
            if (ticket.isBlank()) {
                throw new IllegalStateException("获取钉钉 jsapi_ticket 失败：" + body);
            }
            ticketCache.put(tenantId, "dingtalk", "default", ticket);
            return ticket;
        });
    }

    private String accessToken(String tenantId) {
        String appKey = credentialService.get(tenantId, "dingtalk", "app-key")
                .orElseThrow(() -> new IllegalStateException("钉钉 app-key 未配置"));
        String appSecret = credentialService.get(tenantId, "dingtalk", "app-secret")
                .orElseThrow(() -> new IllegalStateException("钉钉 app-secret 未配置"));
        try {
            String body = restTemplate.getForObject(
                    TOKEN_URL + "?appkey=" + appKey + "&appsecret=" + appSecret, String.class);
            JsonNode json = readTree(body);
            String token = json.path("access_token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("获取钉钉 access_token 失败：" + body);
            }
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("[dingtalk-js] 获取 access_token 异常 tenant={}", tenantId, e);
            throw new IllegalStateException("获取钉钉 access_token 异常：" + e.getMessage());
        }
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
