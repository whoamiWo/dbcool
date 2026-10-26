package com.nocobase.integration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.credential.TenantCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 飞书 JS-SDK（h5Webview）签名。
 *
 * <p>与钉钉/企微不同，飞书 ticket 需通过 <b>POST</b> 且带 Bearer token 获取：
 * <ul>
 *   <li>token：{@code POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal}
 *       （body: app_id / app_secret）</li>
 *   <li>ticket：{@code POST https://open.feishu.cn/open-apis/jssdk/ticket/get}
 *       （header: {@code Authorization: Bearer <tenant_access_token>}），返回 {@code data.ticket}</li>
 * </ul>
 *
 * <p>签名算法与钉钉/企微一致（SHA1），仅 ticket 来源不同。
 *
 * <p>凭证：{@code credentialService.get(tenantId, "feishu", "app-id" / "app-secret")}。
 */
@Component
public class FeishuJsSignatureProvider implements JsSignatureProvider {

    private static final Logger log = LoggerFactory.getLogger(FeishuJsSignatureProvider.class);

    private static final String BASE_URL = "https://open.feishu.cn/open-apis";
    private static final String TOKEN_URL = BASE_URL + "/auth/v3/tenant_access_token/internal";
    private static final String TICKET_URL = BASE_URL + "/jssdk/ticket/get";

    private final TenantCredentialService credentialService;
    private final JsTicketCache ticketCache;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public FeishuJsSignatureProvider(TenantCredentialService credentialService,
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
        return "feishu";
    }

    /** 飞书只有单一 app ticket，type 参数忽略，固定返回 default。 */
    @Override
    public JsSignature sign(String tenantId, String url, String type) {
        String appId = credentialService.get(tenantId, "feishu", "app-id")
                .orElseThrow(() -> new IllegalStateException(
                        "飞书 app-id 未配置（租户=" + tenantId + "），无法生成 JS-SDK 签名"));
        String ticket = ticket(tenantId);
        String nonce = JsSignatureUtil.nonceStr();
        long ts = JsSignatureUtil.currentTimestampSeconds();
        return new JsSignature(appId, nonce, ts, url,
                JsSignatureUtil.sha1Sign(ticket, nonce, ts, url), "default");
    }

    private String ticket(String tenantId) {
        return ticketCache.get(tenantId, "feishu", "default").orElseGet(() -> {
            String token = tenantAccessToken(tenantId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            String body = restTemplate.postForObject(
                    TICKET_URL, new HttpEntity<>("{}", headers), String.class);
            JsonNode json = readTree(body);
            String ticket = json.path("data").path("ticket").asText("");
            if (ticket.isBlank()) {
                throw new IllegalStateException("获取飞书 jssdk ticket 失败：" + body);
            }
            ticketCache.put(tenantId, "feishu", "default", ticket);
            return ticket;
        });
    }

    private String tenantAccessToken(String tenantId) {
        String appId = credentialService.get(tenantId, "feishu", "app-id")
                .orElseThrow(() -> new IllegalStateException("飞书 app-id 未配置"));
        String appSecret = credentialService.get(tenantId, "feishu", "app-secret")
                .orElseThrow(() -> new IllegalStateException("飞书 app-secret 未配置"));
        try {
            String payload = objectMapper.writeValueAsString(
                    java.util.Map.of("app_id", appId, "app_secret", appSecret));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String body = restTemplate.postForObject(
                    TOKEN_URL, new HttpEntity<>(payload, headers), String.class);
            JsonNode json = readTree(body);
            String token = json.path("tenant_access_token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("获取飞书 tenant_access_token 失败：" + body);
            }
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("[feishu-js] 获取 tenant_access_token 异常 tenant={}", tenantId, e);
            throw new IllegalStateException("获取飞书 tenant_access_token 异常：" + e.getMessage());
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
