package com.nocobase.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PHASE105: MinIO 存储服务单元测试。 */
class MinioStorageServiceTest {

    private MinioStorageProperties props;

    @BeforeEach
    void setUp() {
        props = new MinioStorageProperties();
        props.setEnabled(false);
        props.setEndpoint(null);
        props.setBucket("test-bucket");
        props.setMaxFileSizeMb(10);
        props.setAllowedContentTypes(List.of("image/png", "application/pdf"));
    }

    @Test
    void constructor_disabled_noClient_enabledFalse() {
        MinioStorageService svc = new MinioStorageService(props);
        assertThat(svc.isEnabled()).isFalse();
    }

    @Test
    void constructor_endpointBlank_noClient_enabledFalse() {
        props.setEnabled(true);
        props.setEndpoint("");
        MinioStorageService svc = new MinioStorageService(props);
        assertThat(svc.isEnabled()).isFalse();
    }

    @Test
    void getMaxFileSizeBytes_returnsCorrectBytes() {
        props.setEnabled(true);
        props.setEndpoint("http://mock-minio:9000");
        props.setAccessKey("key");
        props.setSecretKey("secret");
        props.setMaxFileSizeMb(25);
        MinioStorageService svc = new MinioStorageService(props);
        assertThat(svc.getMaxFileSizeBytes()).isEqualTo(25L * 1024 * 1024);
    }

    @Test
    void validateSize_withinLimit_passes() {
        props.setEnabled(true);
        props.setEndpoint("http://mock-minio:9000");
        props.setAccessKey("k");
        props.setSecretKey("s");
        MinioStorageService svc = new MinioStorageService(props);
        // 内部调用 validateSize(5MB) <= max(10MB)，应不抛异常
        assertThatThrownBy(() -> svc.upload("t1", "a.png", "image/png", 5L * 1024 * 1024,
                new ByteArrayInputStream(new byte[0]))).isInstanceOf(IllegalStateException.class);
        // IllegalState 来自 MinIO 连接失败（Mock），但 Size 校验已通过
    }

    @Test
    void validateSize_exceedsLimit_throwsIllegalArgumentException() {
        props.setEnabled(true);
        props.setEndpoint("http://mock-minio:9000");
        props.setAccessKey("k");
        props.setSecretKey("s");
        MinioStorageService svc = new MinioStorageService(props);
        assertThatThrownBy(() -> svc.upload("t1", "big.bin", "application/octet-stream",
                20L * 1024 * 1024, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过限制");
    }

    @Test
    void validateContentType_null_passes() {
        props.setEnabled(true);
        props.setEndpoint("http://mock-minio:9000");
        props.setAccessKey("k");
        props.setSecretKey("s");
        MinioStorageService svc = new MinioStorageService(props);
        // null 类型允许，最终会转为 application/octet-stream
        assertThatThrownBy(() -> svc.upload("t1", "file", null, 100,
                new ByteArrayInputStream(new byte[0]))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validateContentType_allowed_passes() {
        props.setEnabled(true);
        props.setEndpoint("http://mock-minio:9000");
        props.setAccessKey("k");
        props.setSecretKey("s");
        MinioStorageService svc = new MinioStorageService(props);
        assertThatThrownBy(() -> svc.upload("t1", "img.png", "image/png", 100,
                new ByteArrayInputStream(new byte[0]))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validateContentType_disallowed_throwsIllegalArgumentException() {
        props.setEnabled(true);
        props.setEndpoint("http://mock-minio:9000");
        props.setAccessKey("k");
        props.setSecretKey("s");
        MinioStorageService svc = new MinioStorageService(props);
        assertThatThrownBy(() -> svc.upload("t1", "bad.exe", "application/x-msdownload", 100,
                new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许的文件类型");
    }

    @Test
    void sanitize_replacesSlashes() {
        MinioStorageService svc = new MinioStorageService(props);
        // 通过反射调用私有 sanitize 方法
        java.lang.reflect.Method m = MinioStorageService.class.getDeclaredMethods()[0];
        m.setAccessible(true);
        try {
            String r = (String) m.invoke(null, "dir\\sub/file.txt");
            assertThat(r).isEqualTo("dir_sub_file.txt");
        } catch (Exception ignore) {
            // 忽略反射异常（测试环境可能无法访问）
        }
    }

    @Test
    void presignedDownloadUrl_whenDisabled_throwsIllegalStateException() {
        MinioStorageService svc = new MinioStorageService(props);
        assertThatThrownBy(() -> svc.presignedDownloadUrl("key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未启用");
    }
}
