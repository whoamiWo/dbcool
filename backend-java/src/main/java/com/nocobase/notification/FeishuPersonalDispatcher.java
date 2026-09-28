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
 * 飞书个人版通知分发器（Webhook）。
 *
 * <p>支持:
 * <ul>
 *   <li>文本消息</li>
 *   <li>卡片消息（飞书卡片构建器）</li>
 * </ul>
 *
 * <p>配置项:
 * <pre>{@code
 * {
 *   "webhookUrl": "https://open.feishu.cn/open-apis/bot/v2/hook/xxx",
 *   "msgType": "text" | "interactive"
 * }
 * }</pre>
 */
@Component
public class FeishuPersonalDispatcher implements NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(FeishuPersonalDispatcher.class);
    private final WebClient webClient;

    public FeishuPersonalDispatcher() {
        this.webClient = WebClient.builder().build();
    }

    @Override
    public Type supportedType() {
        return Type.FEISHU_PERSONAL;
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

            if ("interactive".equals(msgType)) {
                // 卡片消息
                message = buildCardMessage(payload);
            } else {
                // 文本消息
                String text = buildTextMessage(payload);
                message = Map.of("msg_type", "text", "content", Map.of("text", text));
            }

            var response = webClient.post()
                    .uri(webhookUrl)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(message)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) {
                return SendResult.error("empty response from feishu");
            }

            // 飞书返回 {"StatusCode": 0, "Message": "success"}
            Integer statusCode = (Integer) response.get("StatusCode");
            if (statusCode != null && statusCode == 0) {
                return SendResult.ok("feishu webhook sent");
            } else {
                String errorMsg = (String) response.get("Message");
                return SendResult.error("feishu error: " + errorMsg);
            }

        } catch (Exception e) {
            log.warn("send feishu notification failed", e);
            return SendResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 构建文本消息。
     */
    private String buildTextMessage(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();

        // 标题
        String title = (String) payload.get("title");
        if (title != null) {
            sb.append("【").append(title).append("】\n");
        }

        // 内容
        String content = (String) payload.get("content");
        if (content != null) {
            sb.append(content).append("\n");
        }

        // 附加信息
        String eventName = (String) payload.get("eventName");
        if (eventName != null) {
            sb.append("事件：").append(eventName).append("\n");
        }

        // 链接
        String link = (String) payload.get("link");
        if (link != null) {
            sb.append("链接：").append(link);
        }

        return sb.toString();
    }

    /**
     * 构建卡片消息。
     */
    private Map<String, Object> buildCardMessage(Map<String, Object> payload) {
        String title = (String) payload.getOrDefault("title", "通知");
        String content = (String) payload.getOrDefault("content", "");
        String link = (String) payload.get("link");

        var card = Map.of(
                "tag", "lark_card",
                "header", Map.of("title", title),
                "elements", java.util.Arrays.asList(
                        Map.of("tag", "markdown", "content", content)
                )
        );

        if (link != null) {
            var action = Map.of(
                    "tag", "button",
                    "text", Map.of("tag", "plain_text", "content", "查看详情"),
                    "url", link,
                    "type", "primary"
            );
            ((java.util.List) card.get("elements")).add(action);
        }

        return Map.of("msg_type", "interactive", "card", card);
    }
}
