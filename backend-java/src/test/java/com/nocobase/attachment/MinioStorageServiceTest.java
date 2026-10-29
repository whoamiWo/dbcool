package com.nocobase.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MinioStorageService} 单元测试（PHASE 100 覆盖率补测）。
 *
 * <p>只覆盖<b>不依赖真实 MinIO 连接</b>的分支：开关判断、配额/白名单读取、
 * 缩略图生成（纯 ImageIO 逻辑）、MIME 判定、以及「未启用」时的统一降级异常。
 * 与 MinIO 服务端交互的 upload/download 由集成测试承担（本仓库 @SpringBootTest 环境基线失败）。
 */
class MinioStorageServiceTest {

    private MinioStorageProperties props(boolean enabled) {
        MinioStorageProperties p = new MinioStorageProperties();
        p.setEnabled(enabled);
        p.setEndpoint(enabled ? "http://localhost:9000" : "");
        p.setAccessKey("ak");
        p.setSecretKey("sk");
        p.setBucket("test-bucket");
        p.setMaxFileSizeMb(100);
        p.setAllowedContentTypes(List.of("image/png", "image/jpeg", "application/pdf"));
        return p;
    }

    private MinioStorageService svc(boolean enabled) {
        return new MinioStorageService(props(enabled));
    }

    /** 生成一张真实可解码的 PNG（宽高自定），供缩略图测试使用。 */
    private byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ---------- 开关与配额 ----------

    @Test
    @DisplayName("MinIO 未启用 → isEnabled=false 且所有操作降级为 IllegalStateException")
    void disabled_allOperationsThrow() {
        MinioStorageService s = svc(false);
        assertFalse(s.isEnabled());

        InputStream in = new ByteArrayInputStream(new byte[16]);
        assertThrows(IllegalStateException.class,
                () -> s.upload("t1", "a.png", "image/png", 16, in));
        assertThrows(IllegalStateException.class, () -> s.presignedDownloadUrl("k"));
        assertThrows(IllegalStateException.class, () -> s.getObjectBytes("k"));
        assertThrows(IllegalStateException.class, () -> s.exists("k"));
        assertThrows(IllegalStateException.class, () -> s.putObjectBytes("k", new byte[]{1}, "image/png"));
    }

    @Test
    @DisplayName("endpoint 为空 → 视同未启用（构造期即降级）")
    void blankEndpoint_treatedAsDisabled() {
        MinioStorageProperties p = props(true);
        p.setEndpoint("   ");
        MinioStorageService s = new MinioStorageService(p);
        assertFalse(s.isEnabled());
    }

    @Test
    @DisplayName("getMaxFileSizeBytes 按 MB 换算")
    void maxFileSizeBytes() {
        MinioStorageProperties p = props(true);
        p.setMaxFileSizeMb(50);
        assertEquals(50L * 1024 * 1024, new MinioStorageService(p).getMaxFileSizeBytes());
    }

    @Test
    @DisplayName("getAllowedContentTypes 透传配置白名单")
    void allowedContentTypes() {
        MinioStorageProperties p = props(true);
        p.setAllowedContentTypes(List.of("application/json", "text/plain"));
        List<String> types = new MinioStorageService(p).getAllowedContentTypes();
        assertEquals(2, types.size());
        assertTrue(types.contains("application/json"));
    }

    // ---------- 缩略图（纯逻辑，不依赖 MinIO） ----------

    @Test
    @DisplayName("generateThumbnail 正常缩放并输出 JPEG")
    void generateThumbnail_success() throws Exception {
        MinioStorageService s = svc(true);
        byte[] original = png(800, 600);

        byte[] thumb = s.generateThumbnail(original, 200);

        assertNotNull(thumb);
        assertTrue(thumb.length > 0);
        // JPEG magic number FF D8 FF
        assertEquals((byte) 0xFF, thumb[0]);
        assertEquals((byte) 0xD8, thumb[1]);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumb));
        assertNotNull(decoded);
        // 最长边应被压到 200
        assertEquals(200, Math.max(decoded.getWidth(), decoded.getHeight()));
    }

    @Test
    @DisplayName("generateThumbnail 小图不放大（scale>=1 保持原尺寸）")
    void generateThumbnail_smallImageKeepsSize() throws Exception {
        MinioStorageService s = svc(true);
        byte[] original = png(50, 40);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(s.generateThumbnail(original, 200)));
        assertNotNull(decoded);
        assertEquals(50, decoded.getWidth());
        assertEquals(40, decoded.getHeight());
    }

    @Test
    @DisplayName("generateThumbnail 入参为空 → IllegalArgumentException")
    void generateThumbnail_emptyInput() {
        MinioStorageService s = svc(true);
        assertThrows(IllegalArgumentException.class, () -> s.generateThumbnail(null, 200));
        assertThrows(IllegalArgumentException.class, () -> s.generateThumbnail(new byte[0], 200));
    }

    @Test
    @DisplayName("generateThumbnail 非图片字节 → 无法解码 IllegalArgumentException")
    void generateThumbnail_undecodable() {
        MinioStorageService s = svc(true);
        assertThrows(IllegalArgumentException.class,
                () -> s.generateThumbnail("not an image at all".getBytes(), 200));
    }

    // ---------- MIME 判定 ----------

    @Test
    @DisplayName("isImageContentType 仅认 image/ 前缀且忽略大小写")
    void isImageContentType() {
        MinioStorageService s = svc(true);
        assertTrue(s.isImageContentType("image/png"));
        assertTrue(s.isImageContentType("IMAGE/JPEG"));
        assertFalse(s.isImageContentType("application/pdf"));
        assertFalse(s.isImageContentType(null));
        assertFalse(s.isImageContentType("  "));
    }

    // ---------- 启用态下的参数校验（requireEnabled 通过后的分支） ----------

    @Test
    @DisplayName("启用态：getObjectBytes / exists / putObjectBytes 空 key → IllegalArgumentException")
    void enabled_blankKeyRejected() {
        MinioStorageService s = svc(true);
        assertThrows(IllegalArgumentException.class, () -> s.getObjectBytes(null));
        assertThrows(IllegalArgumentException.class, () -> s.getObjectBytes("  "));
        assertThrows(IllegalArgumentException.class, () -> s.exists(null));
        assertThrows(IllegalArgumentException.class, () -> s.putObjectBytes(null, new byte[]{1}, "image/png"));
        assertThrows(IllegalArgumentException.class, () -> s.putObjectBytes("k", null, "image/png"));
        assertThrows(IllegalArgumentException.class, () -> s.putObjectBytes("k", new byte[0], "image/png"));
    }
}
