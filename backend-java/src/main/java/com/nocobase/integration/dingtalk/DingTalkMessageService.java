package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 钉钉消息推送服务 — 发送工作通知（ActionCard / Markdown / OA）。
 *
 * <p><b>Week 3 任务</b>: Wiki 页面发布时，钉钉群收到消息卡片。
 * <ul>
 *   <li>发送工作通知: {@link #sendWorkNotice(String, String, String, Map)}</li>
 *   <li>发送群消息: {@link #sendGroupMessage(String, String, Map)}</li>
 * </ul>
 */
@Service
public class DingTalkMessageService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkMessageService.class);

    private final DingTalkAppService appService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DingTalkMessageService(DingTalkAppService appService) {
        this.appService = appService;
    }

    /** 是否已配置钉钉应用凭证。 */
    private boolean isConfigured() {
        return appService.isConfigured();
    }

    /**
     * 发送钉钉工作通知（ActionCard）。
     *
     * @param agentId     应用 agentId
     * @param recipient   接收人 unionid（逗号分隔）
     * @param title       消息标题
     * @param content     消息内容
     * @return 发送成功与否
     */
    public boolean sendWorkNotice(String agentId, String recipient, String title, Map<String, Object> content) {
        if (!isConfigured()) {
            log.warn("[DingTalk] 工作通知发送失败: 应用未配置");
            return false;
        }

        try {
            String url = "https://oapi.dingtalk.com/topapi/message/send_conversation";
            String body = objectMapper.writeValueAsString(Map.of(
                    "agent_id", agentId,
                    "msg", Map.of(
                            "msgtype", "actionCard",
                            "actionCard", Map.of(
                                    "title", title,
                                    "text", String.valueOf(content.getOrDefault("body", "")),
                                    "single_title", String.valueOf(content.getOrDefault("single_title", "查看详情")),
                                    "single_url", String.valueOf(content.getOrDefault("url", ""))
                            )
                    ),
                    "receiver_user_ids", recipient
            ));

            String response = new org.springframework.web.client.RestTemplate()
                    .postForObject(url, body, String.class);

            JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
            boolean success = json.path("errcode").asInt(-1) == 0;

            if (success) {
                log.info("[DingTalk] 工作通知发送成功: {}", title);
            } else {
                log.warn("[DingTalk] 工作通知发送失败: {}", json.toString());
            }

            return success;

        } catch (Exception e) {
            log.error("[DingTalk] 工作通知发送异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 发送钉钉群消息（Markdown）。
     *
     * @param chatId     群聊 ID
     * @param title      消息标题
     * @param content    消息内容
     * @return 发送成功与否
     */
    public boolean sendGroupMessage(String chatId, String title, Map<String, Object> content) {
        if (!isConfigured()) {
            log.warn("[DingTalk] 群消息发送失败: 应用未配置");
            return false;
        }

        try {
            String url = "https://oapi.dingtalk.com/topapi/message/send_conversation";
            String body = objectMapper.writeValueAsString(Map.of(
                    "chat_id", chatId,
                    "msg", Map.of(
                            "msgtype", "markdown",
                            "markdown", Map.of(
                                    "title", title,
                                    "text", String.valueOf(content.getOrDefault("body", ""))
                            )
                    )
            ));

            String response = new org.springframework.web.client.RestTemplate()
                    .postForObject(url, body, String.class);

            JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
            boolean success = json.path("errcode").asInt(-1) == 0;

            if (success) {
                log.info("[DingTalk] 群消息发送成功: {}", title);
            } else {
                log.warn("[DingTalk] 群消息发送失败: {}", json.toString());
            }

            return success;

        } catch (Exception e) {
            log.error("[DingTalk] 群消息发送异常: {}", e.getMessage(), e);
            return false;
        }
    }
}