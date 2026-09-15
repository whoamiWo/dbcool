package com.nocobase.attachment;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * MinIO / S3 兼容对象存储服务(Week 41 复核 D1.4)。
 *
 * <p>补齐 Week 41 缺失的真实文件存储 —— 此前 attachment 只有元数据契约,
 * 下载端点固定返 501,README 宣称的 MinIO 技术栈从未真正接入。
 *
 * <p><strong>可降级设计</strong>:{@code app.storage.minio.enabled} 默认 false。
 * 未启用时 {@link #isEnabled()} 返回 false,Controller 相应端点返 501,
 * 因此测试环境(无 MinIO)与既有行为完全一致,不会引入启动期连接失败。
 */
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final boolean enabled;
    private final String bucket;
    private final MinioClient client;

    public MinioStorageService(
            @Value("${app.storage.minio.enabled:false}") boolean enabled,
            @Value("${app.storage.minio.endpoint:}") String endpoint,
            @Value("${app.storage.minio.access-key:}") String accessKey,
            @Value("${app.storage.minio.secret-key:}") String secretKey,
            @Value("${app.storage.minio.bucket:nocobase}") String bucket
    ) {
        this.enabled = enabled && endpoint != null && !endpoint.isBlank();
        this.bucket = bucket;

        if (!this.enabled) {
            this.client = null;
            log.info("[storage] MinIO 未启用(enabled=false 或 endpoint 为空) — 附件端点将返 501");
            return;
        }

        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey == null ? "" : accessKey,
                             secretKey == null ? "" : secretKey)
                .build();

        // 确保 bucket 存在;启动期 MinIO 未就绪时仅告警,不阻断启动
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[storage] 已创建 bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("[storage] bucket 检查/创建失败(MinIO 可能未就绪): {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled && client != null;
    }

    /**
     * 上传对象并返回 storageKey。
     *
     * <p>key 结构:{@code <tenantId>/<uuid>/<safeName>} —— 带上租户前缀,
     * 配合多租户隔离(ADR-007)。
     */
    public String upload(String tenantId, String originalName, String contentType,
                         long size, InputStream data) {
        requireEnabled();
        String key = (tenantId == null ? "unknown" : tenantId) + "/"
                + UUID.randomUUID() + "/" + sanitize(originalName);
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(data, size > 0 ? size : -1, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("文件上传失败: " + e.getMessage(), e);
        }
    }

    /** 生成预签名下载 URL(1 小时有效)。 */
    public String presignedDownloadUrl(String storageKey) {
        requireEnabled();
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(storageKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("生成下载链接失败: " + e.getMessage(), e);
        }
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException("对象存储未启用");
        }
    }

    /** 去掉路径分隔符,防止 storageKey 路径穿越。 */
    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "file";
        return name.replaceAll("[/\\\\]", "_");
    }
}
