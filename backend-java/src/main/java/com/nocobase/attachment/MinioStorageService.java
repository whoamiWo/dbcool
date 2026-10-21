package com.nocobase.attachment;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
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

    // ============================================================
    //  PHASE126: 字节读取、存在性判断、图片缩放（缩略图核心能力）
    // ============================================================

    /**
     * 读取对象的全部字节。
     *
     * <p>带大小上限保护:若对象大小超过 {@link #getMaxFileSizeBytes()} 配置,
     * 先通过 {@link #exists} 拿到 size 判断,超限直接抛 {@code IllegalArgumentException}
     * → 由 {@link com.nocobase.config.GlobalExceptionHandler#handleIAE} 映射为 400。
     */
    public byte[] getObjectBytes(String storageKey) {
        requireEnabled();
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey 不能为空");
        }
        Objects.requireNonNull(storageKey, "storageKey 不能为空");
        try {
            long fileSize = exists(storageKey) ? internalClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(storageKey)
                            .build()).size() : 0;
            if (fileSize > getMaxFileSizeBytes()) {
                throw new IllegalArgumentException("文件大小超过限制");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = internalClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(storageKey)
                    .build())) {
                in.transferTo(out);
            }
            return out.toByteArray();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new IllegalArgumentException("对象不存在");
            }
            throw new IllegalStateException("读取对象失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("读取对象失败：" + e.getMessage(), e);
        }
    }

    /**
     * 判断对象是否存在(不读内容,仅通过 stat 查询元信息)。
     */
    public boolean exists(String storageKey) {
        requireEnabled();
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey 不能为空");
        }
        try {
            internalClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(storageKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new IllegalStateException("查询对象状态失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("查询对象状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * 将字节写入 MinIO(用于缩略图回写)。
     *
     * <p>若目标 key 已存在,会覆盖原对象。幂等,可安全重试。
     */
    public void putObjectBytes(String storageKey, byte[] data, String contentType) {
        requireEnabled();
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey 不能为空");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("数据不能为空");
        }
        try {
            internalClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(storageKey)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType != null ? contentType : "image/jpeg")
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("写入对象失败：" + e.getMessage(), e);
        }
    }

    /**
     * 将原始图片字节缩放到最长边不超过 maxEdge 像素,输出 JPEG 格式字节。
     *
     * <p>支持的输入格式:PNG/JPEG/GIF/BMP。不支持 WebP、HEIC 等第三方格式 —
     * 这些场景会抛出 {@code IllegalArgumentException} 供调用方降级为"无缩略图"。
     *
     * @param original 原始图片字节数组
     * @param maxEdge  缩放后的最长边(像素),通常取 200
     * @return JPEG 格式字节数组;若 scale ≤ 1.0 仍会转成 JPEG(统一输出格式)
     */
    public byte[] generateThumbnail(byte[] original, int maxEdge) {
        if (original == null || original.length == 0) {
            throw new IllegalArgumentException("图片数据不能为空");
        }
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(original));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解码图片:" + e.getMessage());
        }
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            throw new IllegalArgumentException("无法解码图片");
        }
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min((double) maxEdge / width, (double) maxEdge / height);
        int targetWidth = scale >= 1.0 ? width : (int) Math.round(width * scale);
        int targetHeight = scale >= 1.0 ? height : (int) Math.round(height * scale);

        // 强制转为 RGB 颜色空间(JPEG 不支持透明通道)
        BufferedImage thumb = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = thumb.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g2d.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g2d.dispose();
            source.flush();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(thumb, "jpeg", out);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法生成缩略图:" + e.getMessage());
        }
        return out.toByteArray();
    }

    /**
     * 判断给定 MIME 类型是否为图片类型。
     *
     * <p>实现极简:仅检查前缀 {@code "image/"}(忽略大小写)。
     * 更精确的判定(如区分 image/svg+xml)可由调用方按需增强。
     */
    public boolean isImageContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        return contentType.toLowerCase().startsWith("image/");
    }

    // ============================================================
    //  内部辅助
    // ============================================================

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
                    String.format("不允许的文件类型:%s。允许的类型:%s",
                            contentType, String.join(", ", allowed))
            );
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "file";
        return name.replaceAll("[/\\\\]", "_");
    }
}
