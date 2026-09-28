package com.nocobase.im;

import com.nocobase.im.entity.ImMessageEntity;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IM 消息搜索服务。
 *
 * <p>提供高级搜索功能，支持:
 * <ul>
 *   <li>关键词全文检索</li>
 *   <li>@提及过滤</li>
 *   <li>发送者过滤</li>
 *   <li>时间范围过滤</li>
 * </ul>
 *
 * <p>PHASE 57 修复说明（实事求是）：本类现有<b>两个</b>公共查询方法 ——
 * {@link #searchMessages} 与 {@link #searchMentions}，二者均已接真：
 * <ul>
 *   <li>{@code searchMessages}：{@code total} 由 {@code repository.countWithFilters}
 *       真实统计（原硬编码 0）</li>
 *   <li>{@code searchMentions}：跨频道由 {@code repository.findMentions} +
 *       {@code countMentions} 真实查询（原返回 {@code Page.empty()}）</li>
 * </ul>
 * 原 {@code countMentions()} / {@code recentMentions()} 两个独立方法已移除 ——
 * 提及统计与"最近提及列表"均由 {@code searchMentions} 覆盖（分页 + total），
 * 避免重复实现。若后续前端需要独立的"未读提及数"，再基于
 * {@code repository.countMentions} 单独开放。
 */
@Service
@Transactional(readOnly = true)
public class MessageSearchService {

    private final ImMessageRepository repository;

    public MessageSearchService(ImMessageRepository repository) {
        this.repository = repository;
    }

    /**
     * 高级搜索消息。
     *
     * @param channelId 频道 ID
     * @param keyword 关键词（可选）
     * @param mentionedByUserId 被谁提及的用户 ID（可选）
     * @param senderId 发送者 ID（可选）
     * @param startTime 开始时间（可选）
     * @param endTime 结束时间（可选）
     * @param page 页码（从 0 开始）
     * @param size 每页大小
     * @return 分页结果
     */
    public Page<ImMessageEntity> searchMessages(
            UUID channelId,
            String keyword,
            UUID mentionedByUserId,
            UUID senderId,
            Instant startTime,
            Instant endTime,
            int page,
            int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        List<ImMessageEntity> results = repository.searchWithFilters(
                channelId, keyword, mentionedByUserId, senderId, startTime, endTime, pageable);

        // PHASE 57 修复：total 改为真实 count 查询（原硬编码 0）
        long total = repository.countWithFilters(
                channelId, keyword, mentionedByUserId, senderId, startTime, endTime);

        return new PageImpl<>(results, pageable, total);
    }

    /**
     * 搜索用户被提及的消息。
     *
     * @param userId 用户 ID
     * @param channelId 频道 ID（可选）
     * @param page 页码
     * @param size 每页大小
     * @return 分页结果
     */
    public Page<ImMessageEntity> searchMentions(
            UUID userId,
            UUID channelId,
            int page,
            int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        // PHASE 57 修复：跨频道 searchMentions 接真（原返回 Page.empty）
        List<ImMessageEntity> results = repository.findMentions(
                null, userId, channelId, pageable);

        long total = repository.countMentions(null, userId, channelId);

        return new PageImpl<>(results, pageable, total);
    }

    /**
     * 获取消息预览（用于搜索结果高亮）。
     *
     * @param message 消息实体
     * @param keyword 关键词
     * @param maxLength 最大长度
     * @return 预览文本（带高亮标记）
     */
    public String getPreview(ImMessageEntity message, String keyword, int maxLength) {
        String content = message.getContent();
        if (content == null || content.isEmpty()) {
            return "";
        }

        int startIndex = Math.max(0, content.indexOf(keyword) - 20);
        int endIndex = Math.min(content.length(), startIndex + maxLength);

        String preview = content.substring(startIndex, endIndex);
        if (startIndex > 0) {
            preview = "... " + preview;
        }
        if (endIndex < content.length()) {
            preview = preview + " ...";
        }

        // 高亮关键词
        if (keyword != null && !keyword.isEmpty()) {
            preview = preview.replaceAll("(?i)(" + Pattern.quote(keyword) + ")", "<strong>$1</strong>");
        }

        return preview;
    }
}