package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.ai.AiAssistantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    /** 注入池化 RestTemplate（RestTemplateConfig Bean），不再 new RestTemplate()。
     *  修复前 new 出来的实例无连接池/超时配置，且被错误用于传 header。 */
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${dingtalk.callback-base-url:http://localhost:8080}")
    private String callbackBaseUrl;

    /** 卡片模板 ID（原硬编码 standard_ai_card，现可配置）。 */
    @Value("${dingtalk.card-template-id:standard_ai_card}")
    private String cardTemplateId;

    /** 卡片实例投递端点（默认钉钉官方 /v1.0/card/instances；可切换为机器人
     *  /v1.0/im/robots/{robotCode}/sendInteractiveCard 等）。 */
    @Value("${dingtalk.card-instance-url:https://api.dingtalk.com/v1.0/card/instances}")
    private String cardInstanceUrl;

    /** 接收人字段名：钉钉给单人投放卡片可用 userId，群会话用 openConversationId。
     *  原代码把 targetUserId 填进 openConversationId（语义错），现可通过配置切换。 */
    @Value("${dingtalk.card-recipient-field:userId}")
    private String cardRecipientField;

    public DingTalkAiCardService(DingTalkAppService appService, AiAssistantService aiService,
                                 RestTemplate restTemplate) {
        this.appService = appService;
        this.aiService = aiService;
        this.restTemplate = restTemplate;
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

        // 4. 投递
        //    修复前：把 headers 塞进 postForObject 的 request body 参数
        //    （第二参是 body 不是 header）→ x-acs-dingtalk-access-token 从未发出，
        //    且 readTree 后直接 ok() 不校验 errcode → 失败也报成功（B4 假完成）。
        //    现改为 HttpHeaders + HttpEntity + postForEntity 正确传 header，并校验响应。
        try {
            // @Value 在未配置/非 Spring 上下文时为 null，此处做兜底，
            // 避免请求体出现 null key/value 与 null URL（既防配置缺失，也让单测可运行）
            final String defaultUrl = "https://api.dingtalk.com/v1.0/card/instances";
            final String defaultTpl = "standard_ai_card";
            final String defaultField = "userId";
            String url = (cardInstanceUrl == null || cardInstanceUrl.isBlank())
                    ? defaultUrl : cardInstanceUrl;
            String tpl = (cardTemplateId == null || cardTemplateId.isBlank())
                    ? defaultTpl : cardTemplateId;
            String field = (cardRecipientField == null || cardRecipientField.isBlank())
                    ? defaultField : cardRecipientField;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cardTemplateId", tpl);
            body.put("outTrackId", outTrackId);
            // 接收人字段按配置写入（默认 userId，修正原 openConversationId 语义错）
            body.put(field, targetUserId);
            body.put("cardData", cardData);
            body.put("callbackRouteKey", "nocobase_ai_card");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-acs-dingtalk-access-token", token);

            ResponseEntity<String> resp = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), String.class);

            // 校验 HTTP 状态码
            if (!resp.getStatusCode().is2xxSuccessful()) {
                String detail = "钉钉卡片投递失败: HTTP " + resp.getStatusCode().value()
                        + " body=" + resp.getBody();
                log.error("[DingTalkAiCard] {}", detail);
                return SendCardResult.fail(detail);
            }

            // 校验业务 errcode（钉钉部分接口在 200 响应体内返回 errmsg/errcode）
            String respBody = resp.getBody();
            JsonNode json = objectMapper.readTree(respBody == null ? "{}" : respBody);
            if (json.hasNonNull("errcode") && json.path("errcode").asInt(0) != 0) {
                String detail = "钉钉卡片投递失败: errcode=" + json.path("errcode").asInt()
                        + " errmsg=" + json.path("errmsg").asText("");
                log.error("[DingTalkAiCard] {}", detail);
                return SendCardResult.fail(detail);
            }
            if (json.hasNonNull("errmsg")
                    && !"ok".equalsIgnoreCase(json.path("errmsg").asText(""))) {
                String detail = "钉钉卡片投递失败: errmsg=" + json.path("errmsg").asText("");
                log.error("[DingTalkAiCard] {}", detail);
                return SendCardResult.fail(detail);
            }

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
