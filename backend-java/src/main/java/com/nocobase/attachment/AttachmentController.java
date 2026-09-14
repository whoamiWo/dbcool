package com.nocobase.attachment;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.tenant.TenantContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 附件 API 契约(Week 41 D1.2).
 *
 * <p>Week 41 范围 — 仅定义契约 + 校验,实际文件存储推迟到 D1.4:
 * <ul>
 *   <li>POST /api/attachments/metadata — 创建附件元数据(返回 storageKey 占位)</li>
 *   <li>GET  /api/attachments/{storageKey} — 获取附件元数据(Week 41 返 501,提示 D1.4 未实现)</li>
 * </ul>
 *
 * <p>生产端点(Week 42+ D1.4 实现 MinIO 集成):
 * <ul>
 *   <li>POST /api/attachments/upload — multipart 上传 → MinIO → 返回 metadata</li>
 *   <li>GET  /api/attachments/{key}/download — 302 重定向到预签名 URL</li>
 * </ul>
 */
@RestController
@Tag(name = "Attachments", description = "附件(Week 41 D1.2 — 仅 metadata,实际存储 Week 42+)")
@RequestMapping("/api/attachments")
public class AttachmentController {

    /**
     * 创建附件 metadata。
     *
     * <p>Week 41 版本: 接收 metadata JSON → 校验 → 返同 metadata(实际文件存储 Week 42+)。
     * 调用方需要先用某种 storage adapter 获得 storageKey,然后注册 metadata 到系统。
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
}
