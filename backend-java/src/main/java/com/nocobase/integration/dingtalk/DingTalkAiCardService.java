package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.ai.AiAssistantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钉钉 AI 卡片投递服务(B4)。
 *
 * <p>主流程:{@code 业务内容 → AI 生成摘要 → 取应用级 token → 投递 interactive card → 卡片按钮
 * callbackRoute 指向 {@code /api/dingtalk/ai-card/callback}}。
 *
 * <p><b>降级原则</b>:AI 摘要调用失败不阻断投递,改用原文截断(与 {@link AiAssistantService}
 * 降级风格一致);卡片投递失败返回明确结果,不静默。
 */
@Service
public class DingTalkAiCardService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkAiCardService.class);

    private final DingTalkAppService appService;
    private final AiAssistantService aiService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${dingtalk.callback-base-url:http://localhost:8080}")
    private String callbackBaseUrl;

    public DingTalkAiCardService(DingTalkAppService appService, AiAssistantService aiService) {
        this.appService = appService;
        this.aiService = aiService;
    }

    /** 投递结果。 */
    public record SendCardResult(boolean success, String detail, String outTrackId) {
        public static SendCardResult ok(String outTrackId, String detail) {
            return new SendCardResult(true, detail, outTrackId);
        }
        public static SendCardResult fail(String detail) {
            return new SendCardResult(false, detail, null);
        }
    }

    /**
     * 向指定钉钉用户投递一张 AI 卡片。
     *
     * @param title           卡片主标题
     * @param content         业务正文(将被 AI 摘要压缩,失败则截断原文)
     * @param targetUserId    钉钉 unionid / userId
     * @param openUrl         可选:主按钮跳转链接
     * @param workflowAction  可选:按钮关联的工作流动作 {@code Map{taskId, action=APPROVE|REJECT}}
     * @return 投递结果
     */
    public SendCardResult sendCard(String title, String content,
                                   String targetUserId, String openUrl,
                                   Map<String, Object> workflowAction) {
        // 1. AI 摘要(降级不阻断)
        String summary = summarize(content);

        // 2. 组装卡片
        String outTrackId = "aicard_" + System.currentTimeMillis();
        Map<String, Object> cardData = buildCard(outTrackId, title, summary, openUrl, workflowAction);

        // 3. 取应用级 token
        String token;
        try {
            token = fetchAppToken();
        } catch (Exception e) {
            log.error("[DingTalkAiCard] 获取 access_token 失败: {}", e.getMessage());
            return SendCardResult.fail("获取钉钉 access_token 失败: " + e.getMessage());
        }
        if (token == null || token.isBlank()) {
            return SendCardResult.fail("钉钉应用未配置或获取 token 失败");
        }

        // 4. 投递(钉钉机器人卡片:POST /v1.0/im/robots/{robotCode}/sendInteractiveCard,
        //    此处以 cardTemplateId + cardData 简化投递,实际可替换为 send_message + interactive card)
        try {
            String url = "https://api.dingtalk.com/v1.0/card/instances";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cardTemplateId", "standard_ai_card");
            body.put("outTrackId", outTrackId);
            body.put("openConversationId", targetUserId);
            body.put("cardData", cardData);
            body.put("callbackRouteKey", "nocobase_ai_card");

            Map<String, String> headers = Map.of(
                    "x-acs-dingtalk-access-token", token,
                    "Content-Type", "application/json");

            String resp = restTemplate.postForObject(url,
                    Map.of("payload", objectMapper.writeValueAsString(body), "headers", headers),
                    String.class);
            JsonNode json = objectMapper.readTree(resp == null ? "{}" : resp);
            log.info("[DingTalkAiCard] 投递成功 outTrackId={} target={}", outTrackId, targetUserId);
            return SendCardResult.ok(outTrackId, json.toString());
        } catch (Exception e) {
            log.error("[DingTalkAiCard] 投递失败: {}", e.getMessage(), e);
            return SendCardResult.fail("卡片投递异常: " + e.getMessage());
        }
    }

    /** 组装卡片数据(按钮 + 摘要)。 */
    private Map<String, Object> buildCard(String outTrackId, String title, String summary,
                                          String openUrl, Map<String, Object> workflowAction) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", title);
        card.put("summary", summary);
        card.put("outTrackId", outTrackId);

        // 按钮区
        List<Map<String, Object>> buttons = new java.util.ArrayList<>();
        if (workflowAction != null) {
            buttons.add(Map.of(
                    "action", "workflow_action",
                    "label", "APPROVE".equals(String.valueOf(workflowAction.get("action"))) ? "同意" : "拒绝",
                    "workflowTaskId", String.valueOf(workflowAction.get("taskId")),
                    "workflowResult", "APPROVE".equals(String.valueOf(workflowAction.get("action")))
                            ? "agree" : "reject",
                    "callbackUrl", callbackBaseUrl + "/api/dingtalk/ai-card/callback"));
        }
        if (openUrl != null && !openUrl.isBlank()) {
            buttons.add(Map.of("action", "open_url", "label", "查看详情", "url", openUrl));
        }
        card.put("buttons", buttons);
        return card;
    }

    /**
     * AI 摘要:调用 {@link AiAssistantService#chat} 生成 ≤200 字摘要,
     * 失败/未启用时降级为原文截断,保证卡片始终可投递。
     */
    String summarize(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> r = aiService.chat(
                    "请把以下内容压缩为不超过 200 字的中文摘要,只输出摘要正文:\n" + content,
                    null, 300, null);
            if (r != null && r.get("data") instanceof Map<?, ?> data
                    && data.get("result") != null) {
                String s = String.valueOf(data.get("result")).trim();
                if (!s.isBlank()) {
                    return s.length() > 200 ? s.substring(0, 200) : s;
                }
            }
        } catch (Exception e) {
            log.warn("[DingTalkAiCard] AI 摘要失败,降级原文: {}", e.getMessage());
        }
        // 降级:原文截断
        return content.length() > 200 ? content.substring(0, 200) + "…" : content;
    }

    /** 获取钉钉应用级 access_token(appKey/appSecret 未配置返回空串)。 */
    private String fetchAppToken() {
        // 复用 appService 凭证;此处直接拼 gettoken 端点
        Map<String, Object> cfg = appService.getAppConfig();
        Object appKeyObj = cfg.get("appKey");
        String appKey = appKeyObj == null ? "" : String.valueOf(appKeyObj);
        String appSecret = appService.getAppSecret();
        if (appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            return "";
        }
        try {
            String url = "https://oapi.dingtalk.com/gettoken?appkey=" + appKey + "&appsecret=" + appSecret;
            String resp = restTemplate.getForObject(url, String.class);
            JsonNode json = objectMapper.readTree(resp == null ? "{}" : resp);
            return json.path("access_token").asText("");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
