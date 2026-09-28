package com.nocobase.im.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 富文本消息解析器。
 *
 * <p>支持解析:
 * <ul>
 *   <li>@提及：@{displayName}:userId</li>
 *   <li>链接：http(s)://...</li>
 *   <li>代码块：```code```</li>
 * </ul>
 */
public class RichTextParser {

    private static final Pattern MENTION_PATTERN = Pattern.compile("@\\{([^}]+)}:([a-f0-9-]+)");
    private static final Pattern LINK_PATTERN = Pattern.compile("https?://[^\\s<>\"']+");
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```([\\s\\S]*?)```");

    /**
     * 解析结果。
     *
     * @param mentions 提及列表
     * @param links 链接列表
     * @param codeBlocks 代码块列表
     */
    public record ParseResult(List<Mention> mentions, List<String> links, List<String> codeBlocks) {}

    /**
     * 提及信息。
     *
     * @param displayName 显示名称
     * @param userId 用户 ID
     * @param position 在原文本中的位置
     */
    public record Mention(String displayName, String userId, int position) {}

    /**
     * 解析富文本消息。
     *
     * @param content 原始消息内容
     * @return 解析结果
     */
    public static ParseResult parse(String content) {
        if (content == null || content.isEmpty()) {
            return new ParseResult(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        List<Mention> mentions = new ArrayList<>();
        List<String> links = new ArrayList<>();
        List<String> codeBlocks = new ArrayList<>();

        // 解析 @提及
        Matcher mentionMatcher = MENTION_PATTERN.matcher(content);
        while (mentionMatcher.find()) {
            mentions.add(new Mention(
                    mentionMatcher.group(1),
                    mentionMatcher.group(2),
                    mentionMatcher.start()
            ));
        }

        // 解析链接
        Matcher linkMatcher = LINK_PATTERN.matcher(content);
        while (linkMatcher.find()) {
            links.add(linkMatcher.group());
        }

        // 解析代码块
        Matcher codeBlockMatcher = CODE_BLOCK_PATTERN.matcher(content);
        while (codeBlockMatcher.find()) {
            codeBlocks.add(codeBlockMatcher.group(1).trim());
        }

        return new ParseResult(mentions, links, codeBlocks);
    }

    /**
     * 将 Mentions 转换为前端可识别的格式。
     *
     * @param userId 当前用户 ID
     * @param displayName 显示名称
     * @return 格式化后的提及字符串
     */
    public static String formatMention(String userId, String displayName) {
        return "@{" + displayName + "}:" + userId;
    }

    /**
     * 检查消息是否包含 @提及。
     *
     * @param content 消息内容
     * @return true 如果包含提及
     */
    public static boolean hasMentions(String content) {
        return content != null && MENTION_PATTERN.matcher(content).find();
    }

    /**
     * 提取所有被提及的用户 ID。
     *
     * @param content 消息内容
     * @return 用户 ID 列表
     */
    public static List<String> extractMentionUserIds(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> userIds = new ArrayList<>();
        Matcher matcher = MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            userIds.add(matcher.group(2));
        }

        return userIds;
    }
}
