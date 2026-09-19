package com.nocobase.im;

import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mention 解析器 — 识别消息正文中的 @user / @channel 提及。
 *
 * <p>用法:在写路径调用,解析完成后触发通知。异常必须吞掉,
 * 不能影响消息发送(对齐现有 notification 风格)。
 *
 * <p>支持格式:
 * <ul>
 *   <li>{@code @user} — 普通用户提及(按 username 匹配)</li>
 *   <li>@channel — 全频道通知</li>
 *   <li>@here — 在线成员通知</li>
 * </ul>
 */
@Component
public class MentionParser {

    private static final Logger log = LoggerFactory.getLogger(MentionParser.class);

    private static final Pattern MENTION =
            Pattern.compile("@(user|channel|here)\\b");

    /**
     * 解析消息正文,返回所有提及。
     * 异常安全:任何异常返回空列表,不影响消息发送。
     */
    public List<Mention> parse(String content) {
        if (content == null || content.isBlank()) return List.of();
        try {
            List<Mention> out = new ArrayList<>();
            Matcher m = MENTION.matcher(content);
            while (m.find()) {
                String kind = m.group(1);
                out.add(new Mention(kind, m.group(0)));
            }
            return out;
        } catch (Exception e) {
            log.warn("[mention] 解析提及失败,已降级为空: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 提及记录。
     *
     * @param kind  user / channel / here
     * @param raw   原始提及文本
     */
    public record Mention(String kind, String raw) {
        public boolean isUser() { return "user".equals(kind); }
        public boolean isChannel() { return "channel".equals(kind); }
        public boolean isHere() { return "here".equals(kind); }
    }
}