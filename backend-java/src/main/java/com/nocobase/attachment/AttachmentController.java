package com.nocobase.attachment;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.tenant.TenantContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 附件 API 契约(Week 41 D1.2) + 文件存储(Week 42+ D1.4 MinIO 集成).
 *
 * <p>已实现端点:
 * <ul>
 *   <li>POST /api/attachments/metadata — 创建附件元数据(需提供 storageKey)</li>
 *   <li>GET  /api/attachments/{storageKey} — 获取附件元数据(501,待扩展)</li>
 *   <li>POST /api/attachments/upload — multipart 上传 → MinIO → 返回 metadata</li>
 *   <li>GET  /api/attachments/{key}/download — 302 重定向到预签名 URL</li>
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
     * 获取附件 metadata(Week 41 暂未实现实际存储,返 501)。
     */
    @GetMapping("/{storageKey}")
    public ResponseEntity<Map<String, Object>> getMetadata(@PathVariable String storageKey) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "附件实际存储 Week 42+ D1.4 实现 (MinIO SDK + 预签名 URL)");
    }

    /**
     * 上传文件(Week 41 复核 D1.4 新增)。
     *
     * <p>未启用对象存储时返 501 —— 与改造前行为一致,不会因缺少 MinIO 而 500。
     */
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
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 下载文件:302 重定向到 MinIO 预签名 URL(Week 41 复核 D1.4 新增)。
     */
    @GetMapping("/{storageKey}/download")
    public ResponseEntity<Void> download(@PathVariable String storageKey) {
        if (!storage.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "对象存储未启用 — 配置 app.storage.minio.enabled=true 后可用(D1.4)");
        }
        String url = storage.presignedDownloadUrl(storageKey);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .build();
    }
}
