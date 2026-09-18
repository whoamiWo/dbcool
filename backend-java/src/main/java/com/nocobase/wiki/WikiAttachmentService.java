package com.nocobase.wiki;

import com.nocobase.attachment.MinioStorageService;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Wiki 附件服务 — 集成 MinIO 对象存储，支持文档页面附件上传与下载。
 *
 * <p>文件存储路径: {tenantId}/wiki/{kbId}/{pageId}/{safeFilename}
 * <p>支持文件类型白名单: pdf, doc, docx, png, jpg, jpeg, gif, md, txt
 * <p>单文件最大: 10MB
 */
@Service
public class WikiAttachmentService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "image/png", "image/jpeg", "image/gif",
            "text/markdown", "text/plain"
    );
    private static final long MAX_SIZE = 10L * 1024 * 1024; // 10MB

    private final MinioStorageService storage;

    public WikiAttachmentService(MinioStorageService storage) {
        this.storage = storage;
    }

    /**
     * 上传附件到 Wiki 文档页面
     * @param file 上传文件
     * @param kbId 知识库 ID
     * @param pageId 文档页面 ID
     * @param tenantId 租户 ID
     * @return 存储 key (如: tenantId/wiki/kbId/pageId/safeName)
     */
    public String uploadToPage(MultipartFile file, UUID kbId, UUID pageId, String tenantId) {
        if (!storage.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "对象存储未启用 — 配置 app.storage.minio.enabled=true 后可用");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        // 文件类型白名单
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的文件类型: " + contentType);
        }
        // 文件大小限制
        if (file.getSize() > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "文件过大，最大允许 10MB");
        }
        try (InputStream in = file.getInputStream()) {
            String key = storage.upload(
                    tenantId,
                    buildWikiKey(kbId, pageId, file.getOriginalFilename()),
                    contentType,
                    file.getSize(),
                    in
            );
            return key;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "附件上传失败: " + e.getMessage());
        }
    }

    /**
     * 生成预签名下载 URL（1小时有效）
     */
    public String getDownloadUrl(String storageKey) {
        if (!storage.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "对象存储未启用");
        }
        return storage.presignedDownloadUrl(storageKey);
    }

    /** 构建 Wiki 专属存储路径 */
    private String buildWikiKey(UUID kbId, UUID pageId, String originalName) {
        String safeName = originalName != null
                ? originalName.replaceAll("[^a-zA-Z0-9_.-]", "_")
                : "file";
        return "wiki/" + kbId + "/" + pageId + "/" + safeName;
    }
}
