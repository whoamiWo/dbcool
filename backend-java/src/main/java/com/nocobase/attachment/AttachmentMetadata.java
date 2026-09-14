package com.nocobase.attachment;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 附件元数据(Week 41 D1.2).
 *
 * <p>存于 record 的 JSONB 字段(attachment 类型字段)。
 *
 * <p>字段:
 * <ul>
 *   <li>storageKey — 对象存储 key(如 MinIO bucket 路径) — Week 41 用简单字符串作占位</li>
 *   <li>originalName — 上传时的原始文件名</li>
 *   <li>contentType — MIME type</li>
 *   <li>size — 字节数</li>
 *   <li>uploadedAt — 上传时间戳</li>
 *   <li>uploadedBy — 上传者 UUID</li>
 * </ul>
 *
 * <p>注意:Week 41 仅实现数据模型 + 校验,实际文件存储(S3/MinIO)推迟到 D1.4
 * (报告 C-R06)。本 Step G1 / D1.2 范围内,storageKey 是不可访问的占位值
 * (如 "placeholder-{uuid}"),用于 API 契约 + ACL 校验。
 */
public record AttachmentMetadata(
        String storageKey,
        String originalName,
        String contentType,
        Long size,
        Instant uploadedAt,
        UUID uploadedBy,
        Map<String, Object> extras
) {
    public AttachmentMetadata {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey 不能为空");
        }
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("originalName 不能为空");
        }
        if (size != null && size < 0) {
            throw new IllegalArgumentException("size 不能为负数");
        }
    }

    /**
     * 校验原始数据是否包含有效附件元数据(Week 41 D1 业务规则)。
     *
     * <p>存到 record 之前调用。
     */
    public static boolean isValid(Object value) {
        if (value == null) return false;
        if (value instanceof AttachmentMetadata meta) {
            return meta.storageKey() != null && !meta.storageKey().isBlank();
        }
        if (value instanceof Map<?, ?> map) {
            return map.containsKey("storageKey") && map.get("storageKey") != null
                    && !((String) map.get("storageKey")).isBlank();
        }
        return false;
    }
}
