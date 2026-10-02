package com.nocobase.attachment;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioStorageProperties properties;
    private final MinioClient internalClient;
    private final String externalUrl;

    public MinioStorageService(MinioStorageProperties properties) {
        this.properties = properties;

        boolean enabled = properties.isEnabled()
                && properties.getEndpoint() != null
                && !properties.getEndpoint().isBlank();

        if (!enabled) {
            this.internalClient = null;
            this.externalUrl = null;
            log.info("[storage] MinIO 未启用 (enabled=false 或 endpoint 为空) — 附件端点将返 501");
            return;
        }

        this.internalClient = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(
                        properties.getAccessKey() == null ? "" : properties.getAccessKey(),
                        properties.getSecretKey() == null ? "" : properties.getSecretKey()
                )
                .build();

        // PHASE69 T3: externalUrl 仅供生成对外预签名 URL 时做字符串重写，
        // 客户端本身始终连接内部端点（容器内必须用服务名访问 MinIO）
        this.externalUrl = (properties.getExternalUrl() != null && !properties.getExternalUrl().isBlank())
                ? properties.getExternalUrl()
                : null;
        
        try {
            String bucket = properties.getBucket();
            if (internalClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                log.info("[storage] bucket 已存在: {}", bucket);
            } else {
                internalClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[storage] 已创建 bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("[storage] bucket 检查/创建失败 (MinIO 可能未就绪): {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled() && internalClient != null;
    }

    public long getMaxFileSizeBytes() {
        return properties.getMaxFileSizeMb() * 1024 * 1024;
    }

    public List<String> getAllowedContentTypes() {
        return properties.getAllowedContentTypes();
    }

    public String upload(String tenantId, String originalName, String contentType,
                          long size, InputStream data) {
        requireEnabled();
        validateSize(size);
        validateContentType(contentType);
        
        String key = (tenantId == null ? "unknown" : tenantId) + "/"
                + UUID.randomUUID() + "/" + sanitize(originalName);
        try {
            internalClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key)
                    .stream(data, size > 0 ? size : -1, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("文件上传失败：" + e.getMessage(), e);
        }
    }

    public String presignedDownloadUrl(String storageKey) {
        requireEnabled();
        try {
            // 如果有 externalUrl，使用它生成预签名 URL（这样生成的链接可以直接被外部访问）
            // 注意：这需要容器能够访问 externalUrl，或者 externalUrl 与 endpoint 相同
            String url;
            if (externalUrl != null && properties.getEndpoint() != null && !externalUrl.equals(properties.getEndpoint())) {
                // 创建临时客户端用于生成外部 URL
                MinioClient externalClient = MinioClient.builder()
                        .endpoint(externalUrl)
                        .credentials(
                                properties.getAccessKey() == null ? "" : properties.getAccessKey(),
                                properties.getSecretKey() == null ? "" : properties.getSecretKey()
                        )
                        .build();
                url = externalClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(properties.getBucket())
                        .object(storageKey)
                        .expiry(1, TimeUnit.HOURS)
                        .build());
            } else {
                // 没有 externalUrl 或内外端点相同，直接使用内部客户端
                url = internalClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(properties.getBucket())
                        .object(storageKey)
                        .expiry(1, TimeUnit.HOURS)
                        .build());
            }
            return url;
        } catch (Exception e) {
            throw new IllegalStateException("生成下载链接失败：" + e.getMessage(), e);
        }
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException("对象存储未启用");
        }
    }

    private void validateSize(long size) {
        long maxBytes = getMaxFileSizeBytes();
        if (size > maxBytes) {
            throw new IllegalArgumentException(
                String.format("文件大小超过限制 (%d MB)", properties.getMaxFileSizeMb())
            );
        }
    }

    private void validateContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return; // Allow default
        }
        List<String> allowed = getAllowedContentTypes();
        if (!allowed.contains(contentType)) {
            throw new IllegalArgumentException(
                String.format("不允许的文件类型：%s。允许的类型：%s", 
                    contentType, String.join(", ", allowed))
            );
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "file";
        return name.replaceAll("[/\\\\]", "_");
    }
}
