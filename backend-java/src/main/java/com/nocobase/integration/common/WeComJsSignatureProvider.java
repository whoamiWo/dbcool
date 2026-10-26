package com.nocobase.integration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.credential.TenantCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 企业微信 JS-SDK 签名。
 *
 * <p>企微有两种 ticket，用途不同：
 * <ul>
 *   <li>{@code corp} ticket → {@code wx.config}（基础 JS-SDK 能力：分享、拍照、定位）
 *       端点 {@code GET https://qyapi.weixin.qq.com/cgi-bin/get_jsapi_ticket?access_token=xxx}</li>
 *   <li>{@code agent} ticket → {@code wx.agentConfig}（应用级能力：选人、打开会话）
 *       端点 {@code GET https://qyapi.weixin.qq.com/cgi-bin/ticket/get?access_token=xxx&type=agent_config}</li>
 * </ul>
 *
 * <p>凭证：{@code credentialService.get(tenantId, "wecom", "corp-id" / "agent-id" / "secret")}。
 */
@Component
public class WeComJsSignatureProvider implements JsSignatureProvider {

    private static final Logger log = LoggerFactory.getLogger(WeComJsSignatureProvider.class);

    private static final String TOKEN_URL = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";
    private static final String CORP_TICKET_URL = "https://qyapi.weixin.qq.com/cgi-bin/get_jsapi_ticket";
    private static final String AGENT_TICKET_URL = "https://qyapi.weixin.qq.com/cgi-bin/ticket/get";

    private final TenantCredentialService credentialService;
    private final JsTicketCache ticketCache;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WeComJsSignatureProvider(TenantCredentialService credentialService,
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
        return "wecom";
    }

    @Override
    public JsSignature sign(String tenantId, String url, String type) {
        // 企微 wx.config 的 appId 是 corpId（不是 agentId）
        String corpId = credentialService.get(tenantId, "wecom", "corp-id")
                .orElseThrow(() -> new IllegalStateException(
                        "企微 corp-id 未配置（租户=" + tenantId + "），无法生成 JS-SDK 签名"));

        boolean isAgent = "agent".equalsIgnoreCase(type);
        String ticketType = isAgent ? "agent" : "corp";
        String ticket = ticket(tenantId, ticketType);

        String nonce = JsSignatureUtil.nonceStr();
        long ts = JsSignatureUtil.currentTimestampSeconds();
        return new JsSignature(corpId, nonce, ts, url,
                JsSignatureUtil.sha1Sign(ticket, nonce, ts, url), ticketType);
    }

    private String ticket(String tenantId, String ticketType) {
        return ticketCache.get(tenantId, "wecom", ticketType).orElseGet(() -> {
            String token = accessToken(tenantId);
            String url;
            if ("agent".equals(ticketType)) {
                url = AGENT_TICKET_URL + "?access_token=" + token + "&type=agent_config";
            } else {
                url = CORP_TICKET_URL + "?access_token=" + token;
            }
            String body = restTemplate.getForObject(url, String.class);
            JsonNode json = readTree(body);
            String ticket = json.path("ticket").asText("");
            if (ticket.isBlank()) {
                throw new IllegalStateException("获取企微 " + ticketType + " ticket 失败：" + body);
            }
            ticketCache.put(tenantId, "wecom", ticketType, ticket);
            return ticket;
        });
    }

    private String accessToken(String tenantId) {
        String corpId = credentialService.get(tenantId, "wecom", "corp-id")
                .orElseThrow(() -> new IllegalStateException("企微 corp-id 未配置"));
        String secret = credentialService.get(tenantId, "wecom", "secret")
                .orElseThrow(() -> new IllegalStateException("企微 secret 未配置"));
        try {
            String body = restTemplate.getForObject(
                    TOKEN_URL + "?corpid=" + corpId + "&corpsecret=" + secret, String.class);
            JsonNode json = readTree(body);
            String token = json.path("access_token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("获取企微 access_token 失败：" + body);
            }
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("[wecom-js] 获取 access_token 异常 tenant={}", tenantId, e);
            throw new IllegalStateException("获取企微 access_token 异常：" + e.getMessage());
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
