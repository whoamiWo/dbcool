package com.nocobase.notification;

import com.nocobase.notification.NotificationChannelEntity.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * 企业微信个人版通知分发器（Webhook）。
 *
 * <p>支持:
 * <ul>
 *   <li>文本消息</li>
 *   <li>Markdown 消息</li>
 *   <li>卡片消息</li>
 * </ul>
 *
 * <p>配置项:
 * <pre>{@code
 * {
 *   "webhookUrl": "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx",
 *   "msgType": "text" | "markdown" | "news"
 * }
 * }</pre>
 */
@Component
public class WeChatPersonalDispatcher implements NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WeChatPersonalDispatcher.class);
    private final WebClient webClient;

    public WeChatPersonalDispatcher() {
        this.webClient = WebClient.builder().build();
    }

    @Override
    public Type supportedType() {
        return Type.WECHAT_PERSONAL;
    }

    @Override
    public SendResult send(NotificationChannelEntity channel, String recipient, Map<String, Object> payload) {
        try {
            String webhookUrl = (String) channel.getConfig().get("webhookUrl");
            if (webhookUrl == null || webhookUrl.isEmpty()) {
                return SendResult.error("missing webhookUrl in config");
            }

            String msgType = (String) channel.getConfig().getOrDefault("msgType", "text");
            Map<String, Object> message;

            if ("markdown".equals(msgType)) {
                message = buildMarkdownMessage(payload);
            } else if ("news".equals(msgType)) {
                message = buildNewsMessage(payload);
            } else {
                String text = buildTextMessage(payload);
                message = Map.of("msgtype", "text", "text", Map.of("content", text));
            }

            var response = webClient.post()
                    .uri(webhookUrl)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(message)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) {
                return SendResult.error("empty response from wechat");
            }

            Integer errcode = (Integer) response.get("errcode");
            if (errcode != null && errcode == 0) {
                return SendResult.ok("wechat personal webhook sent");
            } else {
                String errorMsg = (String) response.get("errmsg");
                return SendResult.error("wechat error: " + errorMsg);
            }

        } catch (Exception e) {
            log.warn("send wechat personal notification failed", e);
            return SendResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String buildTextMessage(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();

        String title = (String) payload.get("title");
        if (title != null) {
            sb.append("【").append(title).append("】\n");
        }

        String content = (String) payload.get("content");
        if (content != null) {
            sb.append(content).append("\n");
        }

        String eventName = (String) payload.get("eventName");
        if (eventName != null) {
            sb.append("事件：").append(eventName).append("\n");
        }

        String link = (String) payload.get("link");
        if (link != null) {
            sb.append("链接：").append(link);
        }

        return sb.toString();
    }

    private Map<String, Object> buildMarkdownMessage(Map<String, Object> payload) {
        String title = (String) payload.getOrDefault("title", "通知");
        String content = (String) payload.getOrDefault("content", "");
        String link = (String) payload.get("link");

        StringBuilder markdown = new StringBuilder();
        markdown.append("**").append(title).append("**\n\n");
        markdown.append(content);
        if (link != null) {
            markdown.append("\n\n[查看详情](").append(link).append(")");
        }

        return Map.of("msgtype", "markdown", "markdown", Map.of("content", markdown.toString()));
    }

    private Map<String, Object> buildNewsMessage(Map<String, Object> payload) {
        String title = (String) payload.getOrDefault("title", "通知");
        String content = (String) payload.getOrDefault("content", "");
        String link = (String) payload.get("link");
        String imageUrl = (String) payload.get("imageUrl");

        var articles = java.util.Arrays.asList(
                Map.of(
                        "title", title,
                        "description", content,
                        "url", link != null ? link : "#",
                        "picurl", imageUrl != null ? imageUrl : ""
                )
        );

        return Map.of("msgtype", "news", "news", Map.of("articles", articles));
    }
}
