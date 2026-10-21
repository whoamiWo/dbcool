package com.nocobase.integration.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.common.InboundMessageService;
import com.nocobase.integration.credential.TenantCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slack 应用服务：OAuth2、事件处理、配置管理。
 */
@Service
public class SlackAppService {
    private static final Logger log = LoggerFactory.getLogger(SlackAppService.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${slack.default-tenant-id:tenant_default}")
    private String defaultTenantId;

    @Value("${slack.default-channel-id:}")
    private String defaultChannelId;

    /** PHASE99 T1: 租户级凭证服务（优先于全局 @Value） */
    private final TenantCredentialService credentialService;

    // 以下 @Value 字段保留为**回退默认值**：租户级未配置时使用，存量部署零破坏
    @Value("${slack.client-id:}")
    private String clientId;

    @Value("${slack.client-secret:}")
    private String clientSecret;

    @Value("${slack.redirect-uri:}")
    private String redirectUri;

    @Value("${slack.signing-secret:}")
    private String signingSecret;

    @Value("${slack.bot-token:}")
    private String botToken;

    @Value("${slack.team-id:}")
    private String teamId;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InboundMessageService inboundMessageService;

    /** 存储已安装的 Slack 工作区配置 */
    private final Map<String, SlackWorkspaceConfig> workspaces = new ConcurrentHashMap<>();

    public SlackAppService(InboundMessageService inboundMessageService,
                           TenantCredentialService credentialService) {
        this.inboundMessageService = inboundMessageService;
        this.credentialService = credentialService;
    }

    /**
     * 按 key 解析凭证：租户级优先，回退全局 @Value。
     *
     * @param tenantId 租户 ID；传 null 时只取全局（用于无租户上下文的场景）
     */
    private Optional<String> resolveForKey(String key, String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            Optional<String> tenantVal = credentialService.get(tenantId, "slack", key);
            if (tenantVal.isPresent()) {
                return tenantVal;
            }
        }
        // 回退全局 @Value
        String fallback = switch (key) {
            case "client-id" -> clientId;
            case "client-secret" -> clientSecret;
            case "redirect-uri" -> redirectUri;
            case "signing-secret" -> signingSecret;
            case "bot-token" -> botToken;
            case "team-id" -> teamId;
            default -> null;
        };
        return (fallback != null && !fallback.isBlank()) ? Optional.of(fallback) : Optional.empty();
    }

    public String getOAuthUrl() {
        String clientId = resolveForKey("client-id", null).orElse("");
        if (clientId.isBlank()) {
            throw new IllegalStateException("Slack client-id 未配置");
        }
        String redirect = resolveForKey("redirect-uri", null).orElse("");
        String url = "https://slack.com/oauth/v2/authorize" +
                "?client_id=" + clientId +
                "&scope=chat:write,commands,im:write" +
                "&user_scope=" +
                "&redirect_uri=" + redirect;
        return url;
    }

    public Map<String, Object> handleOAuthCallback(String code) throws Exception {
        String clientId = resolveForKey("client-id", null).orElse("");
        String clientSecret = resolveForKey("client-secret", null).orElse("");
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException("Slack OAuth 凭证未配置");
        }

        // 真实调用 Slack API：code 换取 bot token
        Map<String, String> params = new HashMap<>();
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);
        params.put("code", code);
        String redirect = resolveForKey("redirect-uri", null).orElse("");
        if (!redirect.isBlank()) {
            params.put("redirect_uri", redirect);
        }

        JsonNode resp = restTemplate.postForObject(
                "https://slack.com/api/oauth.v2.access",
                params,
                JsonNode.class
        );

        if (resp == null) {
            throw new RuntimeException("Slack OAuth 响应为空");
        }
        boolean ok = resp.path("ok").asBoolean(false);
        if (!ok) {
            String error = resp.path("error").asText("unknown");
            throw new RuntimeException("Slack OAuth 失败：" + error);
        }

        String accessToken = resp.path("access_token").asText("");
        String newTeamId = resp.path("team").path("id").asText("");
        String teamName = resp.path("team").path("name").asText("");
        String botUserId = resp.path("bot_user_id").asText("");

        if (accessToken.isBlank()) {
            throw new RuntimeException("Slack 未返回 access_token");
        }

        // 保存到内存 Map（生产环境应持久化到数据库或 Vault）
        workspaces.put(newTeamId, new SlackWorkspaceConfig(
                newTeamId, teamName, botUserId, accessToken, "",
                java.time.Instant.now().toString()
        ));

        log.info("[Slack] OAuth 安装成功: team={} ({})", teamName, newTeamId);
        return Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "teamId", newTeamId,
                        "teamName", teamName,
                        "botUserId", botUserId
                )
        );
    }

    public void handleEvent(JsonNode payload) {
        // 真实 Slack 事件是**两层**结构:
        //   {"type":"event_callback","event_id":"Ev...",
        //    "event":{"type":"message","text":...,"channel":...,"user":...,"ts":...}}
        // 早期实现直接取顶层 type 判 "message",而顶层恒为 "event_callback"
        // → 真实消息永远进不了处理分支:接口照样返回 200,但什么都没落地
        //   (实测:用真实格式 POST → 200,消息命中 0,日志无任何 [Inbound])。
        // 这里先展开 event 子对象,同时兼容扁平格式(单测/简化调用)。
        JsonNode event = payload;
        if ("event_callback".equals(payload.path("type").asText("")) && payload.hasNonNull("event")) {
            event = payload.path("event");
        }

        String type = event.path("type").asText();
        String subtype = event.path("subtype").asText("");

        // 处理 message 事件
        if ("message".equals(type) && !"bot_message".equals(subtype)) {
            String text = event.path("text").asText("");
            String channel = event.path("channel").asText("");
            String user = event.path("user").asText("");
            // event_id 位于**外层**;缺失时回退到 event.ts(同样具备唯一性)
            String eventId = payload.path("event_id").asText(event.path("ts").asText(""));

            // 使用 InboundMessageService 进行落库 + 广播
            if (inboundMessageService != null && eventId != null && !eventId.isBlank()) {
                inboundMessageService.processInboundMessage(
                        defaultTenantId,
                        null, // 使用 defaultChannelId
                        "slack",
                        eventId,
                        user,
                        text,
                        null
                );
            } else {
                log.warn("[Slack] 入站消息无法处理：缺少 eventId 或 inboundMessageService=null");
            }
        }

        log.debug("[Slack] 事件类型：type={}, subtype={}", type, subtype);
    }

    public boolean isConfigured() {
        return resolveForKey("client-id", null).isPresent()
                && resolveForKey("client-secret", null).isPresent()
                && resolveForKey("signing-secret", null).isPresent();
    }

    public String getSigningSecret() {
        return resolveForKey("signing-secret", null).orElse("");
    }

    public String getBotToken() {
        return resolveForKey("bot-token", null).orElse("");
    }

    public String getTeamId() {
        return resolveForKey("team-id", null).orElse("");
    }
}
