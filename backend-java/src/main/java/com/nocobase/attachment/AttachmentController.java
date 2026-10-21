package com.nocobase.attachment;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 附件 API 契约(Week 41 D1.2) + 文件存储(Week 42+ D1.4 MinIO 集成).
 *
 * <p>已实现端点:
 * <ul>
 *   <li>POST /api/attachments/metadata — 创建附件元数据(需提供 storageKey)</li>
 *   <li>GET  /api/attachments?storageKey=... — 获取附件元数据(需 query param)</li>
 *   <li>POST /api/attachments/upload — multipart 上传 → MinIO → 返回 metadata</li>
 *   <li>GET  /api/attachments/download?storageKey=... — 302 重定向到预签名 URL</li>
 * </ul>
 */
@RestController
@Tag(name = "Attachments", description = "附件(Week 41 D1.2 — 仅 metadata,实际存储 Week 42+)")
@RequestMapping("/api/attachments")
public class AttachmentController {

    private final MinioStorageService storage;

    public AttachmentController(MinioStorageService storage) {
        this.storage = storage;
    }

    /**
     * 创建附件 metadata。
     *
     * <p>接收 metadata JSON → 校验 → 返回 metadata。
     * 调用方可通过 /upload 端点先上传文件获得真实 storageKey,再注册 metadata。
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/metadata")
    public Map<String, Object> createMetadata(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        if (!AttachmentMetadata.isValid(body)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "无效附件 metadata(需要 storageKey + originalName)");
        }

        // Week 41: 仅校验 + 返回,文件本身未存储
        // Week 42+: 这里会调 MinIO.putObject() 然后存 metadata 到 records
        AttachmentMetadata meta = new AttachmentMetadata(
                (String) body.get("storageKey"),
                (String) body.get("originalName"),
                (String) body.getOrDefault("contentType", "application/octet-stream"),
                body.get("size") instanceof Number n ? n.longValue() : null,
                Instant.now(),
                user.userId(),
                body
        );

        return Map.of(
                "code", 0,
                "message", "metadata registered (file storage Week 42+ D1.4)",
                "data", Map.of(
                        "storageKey", meta.storageKey(),
                        "originalName", meta.originalName(),
                        "contentType", meta.contentType(),
                        "size", meta.size() != null ? meta.size() : 0,
                        "tenantId", TenantContext.currentTenantId()
                )
        );
    }

    /**
     * 获取附件 metadata。
     *
     * <p>PHASE69 T3: 使用 query param 避免路径编码问题。
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping(params = "storageKey")
    public ResponseEntity<Map<String, Object>> getMetadata(@RequestParam String storageKey) {
        if (!storage.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "对象存储未启用");
        }
        String tenantId = TenantContext.currentTenantId();
        if (!storageKey.startsWith(tenantId + "/")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "无权访问该附件");
        }
        String url = storage.presignedDownloadUrl(storageKey);
        return ResponseEntity.ok(Map.of(
                "storageKey", storageKey,
                "downloadUrl", url,
                "tenantId", tenantId
        ));
    }

    /**
     * 上传文件(Week 41 复核 D1.4 新增)。
     *
     * <p>未启用对象存储时返 501 —— 与改造前行为一致,不会因缺少 MinIO 而 500。
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/upload")
    public Map<String, Object> upload(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        if (!storage.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "对象存储未启用 — 配置 app.storage.minio.enabled=true 后可用(D1.4)");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        String tenantId = TenantContext.currentTenantId();
        try (InputStream in = file.getInputStream()) {
            String key = storage.upload(tenantId, file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), in);
            return Map.of(
                    "code", 0,
                    "message", "uploaded",
                    "data", Map.of(
                            "storageKey", key,
                            "originalName", file.getOriginalFilename() != null
                                    ? file.getOriginalFilename() : "file",
                            "contentType", file.getContentType() != null
                                    ? file.getContentType() : "application/octet-stream",
                            "size", file.getSize(),
                            "tenantId", tenantId
                    )
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 下载文件:302 重定向到 MinIO 预签名 URL(Week 41 复核 D1.4 新增)。
     *
     * <p>PHASE69 T3: 使用 query param 避免路径编码问题。
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/download")
    public ResponseEntity<Void> download(@RequestParam String storageKey) {
        if (!storage.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "对象存储未启用 — 配置 app.storage.minio.enabled=true 后可用 (D1.4)");
        }
        validateStorageKey(storageKey);
        String url = storage.presignedDownloadUrl(storageKey);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .build();
    }

    /**
     * 在线预览附件原始内容(Week 41+ D1.4 增强)。
     *
     * <p>返回字节流,由浏览器根据 Content-Type 渲染图片/PDF 等,非图片则触发下载。
     * Content-Disposition 固定为 inline,不触发浏览器下载对话框。
     *
     * @param storageKey 对象存储键,格式 {tenantId}/{uuid}/{sanitized-name}
     * @return 字节流 + 推断的 Content-Type
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/preview")
    @Operation(summary = "附件预览", description = "直接返回附件字节流,浏览器内嵌展示")
    public ResponseEntity<byte[]> preview(@RequestParam String storageKey) {
        byte[] bytes = readWithTenantCheck(storageKey);
        String contentType = inferContentType(bytes, storageKey);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + decodeFilename(storageKey) + "\"")
                .body(bytes);
    }

    /**
     * 获取图片缩略图(Week 41+ D1.4 增强)。
     *
     * <p>若缩略图已回写则直接返回缓存版本;首次请求时调用 MinioStorageService.generateThumbnail
     * 并回写至同一 bucket 下 {path}/.thumb/{name}.jpg。
     *
     * <p>非图片类附件(如 PDF/doc/xlsx)或解码失败时将返 415 Unsupported Media Type,
     * 前端需降级为类型图标。
     *
     * @param storageKey 原始对象存储键
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/thumbnail")
    @Operation(summary = "附件缩略图", description = "返回缩放后的 JPEG 字节流(最长边 200px)")
    public ResponseEntity<byte[]> thumbnail(@RequestParam String storageKey) {
        if (!storage.isImageContentType(inferContentTypeFromKey(storageKey))) {
            // 非图片:返 415,前端降级为类型图标
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "仅支持图片类附件生成缩略图");
        }
        // 尝试返回已缓存的缩略图
        String thumbKey = thumbKey(storageKey);
        if (storage.exists(thumbKey)) {
            byte[] thumbBytes = storage.getObjectBytes(thumbKey);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                    .body(thumbBytes);
        }
        // 首读:生成并回写
        byte[] originalBytes = readWithTenantCheck(storageKey);
        try {
            byte[] thumbBytes = storage.generateThumbnail(originalBytes, 200);
            storage.putObjectBytes(thumbKey, thumbBytes, "image/jpeg");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                    .body(thumbBytes);
        } catch (IllegalArgumentException e) {
            // 解码失败 → 415
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
        }
    }

    /**
     * 私有:抽取路径穿越 + 租户前缀校验为公共校验方法,供 download/preview/thumbnail 共用。
     *
     * <p>这样做确保三个端点遵循完全一致的校验逻辑,杜绝新增端点漏加某项校验的风险。
     */
    private void validateStorageKey(String storageKey) {
        if (storageKey == null || storageKey.contains("..") || storageKey.startsWith("/") || storageKey.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的存储键");
        }
        String tenantId = TenantContext.currentTenantId();
        if (!storageKey.startsWith(tenantId + "/")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该对象");
        }
    }

    /**
     * 读取对象字节并强制租户校验;同时捕获 IllegalArgumentException(含 NoSuchKey)映射为 404。
     */
    private byte[] readWithTenantCheck(String storageKey) {
        validateStorageKey(storageKey);
        try {
            return storage.getObjectBytes(storageKey);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * 从 storageKey 末尾原文件名推断 Content-Type。
     *
     * <p>策略:取最后一层文件名(extension)→ MIME 映射(硬编码常见类型)→ 回退 application/octet-stream。
     */
    private String inferContentTypeFromKey(String storageKey) {
        String name = storageKey.substring(storageKey.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            return switch (ext) {
                case "png" -> "image/png";
                case "jpg", "jpeg" -> "image/jpeg";
                case "gif" -> "image/gif";
                case "webp" -> "image/webp";
                case "svg" -> "image/svg+xml";
                case "pdf" -> "application/pdf";
                case "doc" -> "application/msword";
                case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "xls" -> "application/vnd.ms-excel";
                case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                default -> "application/octet-stream";
            };
        }
        return "application/octet-stream";
    }

    /**
     * 从字节头推断 Content-Type(Magic Bytes),仅对图片类型生效。
     *
     * <p>PNG 以 0x89504E47 开头,JPEG 以 0xFFD8FF 开头,GIF 以 0x474946 开头,BMP 以 0x424D 开头。
     * 无法识别则回退到基于文件名的推断。
     */
    private String inferContentType(byte[] bytes, String storageKey) {
        if (bytes.length >= 4) {
            // PNG: 89 50 4E 47
            if (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
                return "image/png";
            }
            // JPEG: FF D8 FF
            if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
                return "image/jpeg";
            }
            // GIF: 47 49 46
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
                return "image/gif";
            }
            // BMP: 42 4D
            if (bytes[0] == 'B' && bytes[1] == 'M') {
                return "image/bmp";
            }
        }
        return inferContentTypeFromKey(storageKey);
    }

    /**
     * 从 storageKey 末尾提取原始文件名(用于 Content-Disposition)。
     */
    private String decodeFilename(String storageKey) {
        int lastSlash = storageKey.lastIndexOf('/');
        return lastSlash >= 0 ? storageKey.substring(lastSlash + 1) : "unknown";
    }

    /**
     * 构造缩略图对象的 storageKey:{原 key 父目录}/.thumb/{原文件名}.jpg。
     *
     * <p>示例: "tenant1/abc-123/photo.png" → "tenant1/abc-123/.thumb/photo.jpg"
     */
    private String thumbKey(String storageKey) {
        int lastSlash = storageKey.lastIndexOf('/');
        String dir = lastSlash >= 0 ? storageKey.substring(0, lastSlash) : ".";
        String name = lastSlash >= 0 ? storageKey.substring(lastSlash + 1) : storageKey;
        String baseName = name.lastIndexOf('.') > 0 ? name.substring(0, name.lastIndexOf('.')) : name;
        return dir + "/.thumb/" + baseName + ".jpg";
    }
}
