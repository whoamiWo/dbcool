package com.nocobase.im;

import com.nocobase.im.entity.ImMessageEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 *   <li>关键词全文检索（应用层分词 + ILIKE，H2/PG 双库通用）</li>
 *   <li>@提及过滤（@{displayName}:userId 格式）</li>
 *   <li>发送者过滤</li>
 *   <li>时间范围过滤</li>
 * </ul>
 *
 * <p>PHASE 61 修复说明：本类此前为死代码（src/main 0 注入点），现已接入
 * {@code ImMessageController#advancedSearch} 与 {@code ImMessageController#mentions}。
 * 已修缺陷：searchMentions 原 tenantId 硬传 null 导致恒返回空。
 */
@Service
@Transactional(readOnly = true)
public class MessageSearchService {

    private final ImMessageRepository repository;

    public MessageSearchService(ImMessageRepository repository) {
        this.repository = repository;
    }

    /**
     * 高级搜索消息（应用层分词多词 OR 检索提升中文召回）。
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
            String tenantId,
            String keyword,
            UUID mentionedByUserId,
            UUID senderId,
            Instant startTime,
            Instant endTime,
            int page,
            int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        String mentionedByStr = mentionedByUserId != null ? mentionedByUserId.toString() : null;

        // 租户过滤下推到 SQL：只返回 m.tenantId = 当前租户 的消息。
        // （此前版本用 "m.tenantId = ch.tenantId" 与传入频道的租户自比，
        //   攻击者传他人 channelId 时两边恒等 → 过滤形同虚设。）
        List<ImMessageEntity> results = repository.searchWithFullFilters(
                channelId, tenantId, keyword, mentionedByStr, senderId, startTime, endTime, pageable);

        long total = repository.countWithFullFilters(
                channelId, tenantId, keyword, mentionedByStr, senderId, startTime, endTime);

        return new PageImpl<>(results, pageable, total);
    }

    /**
     * 搜索用户被提及的消息（跨频道）。
     *
     * @param tenantId 租户 ID
     * @param userId 用户 ID
     * @param channelId 频道 ID（可选）
     * @param page 页码
     * @param size 每页大小
     * @return 分页结果
     */
    public Page<ImMessageEntity> searchMentions(
            String tenantId,
            UUID userId,
            UUID channelId,
            int page,
            int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        String userIdStr = userId.toString();

        List<ImMessageEntity> results = repository.findMentions(
                tenantId, userIdStr, channelId, pageable);

        long total = repository.countMentions(tenantId, userIdStr, channelId);

        return new PageImpl<>(results, pageable, total);
    }

    /**
     * 获取消息预览（纯文本，高亮由前端完成，避免后端拼 HTML 引入 XSS）。
     *
     * @param message 消息实体
     * @param keyword 关键词
     * @param maxLength 最大长度
     * @return 预览文本（无 HTML 标记）
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

        return preview;
    }
}