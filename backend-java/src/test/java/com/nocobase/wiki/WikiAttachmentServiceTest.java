package com.nocobase.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.attachment.MinioStorageService;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** PHASE105: WikiAttachmentService 测试（存储开关 / 白名单 / 大小限制 / 异常降级）。 */
class WikiAttachmentServiceTest {

    private MinioStorageService storage;
    private WikiAttachmentService service;

    private static final String TENANT = "t1";
    private static final UUID KB_ID = UUID.randomUUID();
    private static final UUID PAGE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        storage = mock(MinioStorageService.class);
        service = new WikiAttachmentService(storage);
    }

    private MultipartFile file(String contentType, long size, String originalName) {
        MultipartFile f = mock(MultipartFile.class);
        when(f.isEmpty()).thenReturn(size == 0);
        when(f.getContentType()).thenReturn(contentType);
        when(f.getSize()).thenReturn(size);
        when(f.getOriginalFilename()).thenReturn(originalName);
        return f;
    }

    // ── 存储未启用（501） ────────────────────────────────────
    @Test
    void uploadToPage_storageDisabled_throws501() {
        when(storage.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.uploadToPage(file("application/pdf", 10, "a.pdf"), KB_ID, PAGE_ID, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_IMPLEMENTED));
    }

    @Test
    void getDownloadUrl_storageDisabled_throws501() {
        when(storage.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.getDownloadUrl("key"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_IMPLEMENTED));
    }

    // ── 入参校验（400） ─────────────────────────────────────
    @Test
    void uploadToPage_nullFile_throws400() {
        when(storage.isEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.uploadToPage(null, KB_ID, PAGE_ID, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void uploadToPage_emptyFile_throws400() {
        when(storage.isEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.uploadToPage(file("application/pdf", 0, "a.pdf"), KB_ID, PAGE_ID, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void uploadToPage_disallowedType_throws400() {
        when(storage.isEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.uploadToPage(file("application/x-sh", 10, "evil.sh"), KB_ID, PAGE_ID, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void uploadToPage_nullContentType_throws400() {
        when(storage.isEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.uploadToPage(file(null, 10, "a.pdf"), KB_ID, PAGE_ID, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void uploadToPage_exceedsMaxSize_throws400() {
        when(storage.isEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.uploadToPage(
                file("application/pdf", 11L * 1024 * 1024, "big.pdf"), KB_ID, PAGE_ID, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── 成功路径 ────────────────────────────────────────────
    @Test
    void uploadToPage_validPDF_returnsKey() throws Exception {
        when(storage.isEnabled()).thenReturn(true);
        MultipartFile f = file("application/pdf", 1024, "报告 v1.pdf");
        when(f.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[1024]));
        when(storage.upload(eq(TENANT), anyString(), eq("application/pdf"), eq(1024L), any()))
                .thenReturn(TENANT + "/wiki/" + KB_ID + "/" + PAGE_ID + "/报告_v1.pdf");

        String key = service.uploadToPage(f, KB_ID, PAGE_ID, TENANT);

        assertThat(key).contains("/wiki/").contains(KB_ID.toString());
        verify(storage).upload(eq(TENANT), anyString(), eq("application/pdf"), anyLong(), any());
    }

    @Test
    void uploadToPage_allowedImageTypes() throws Exception {
        when(storage.isEnabled()).thenReturn(true);
        for (String type : new String[]{"image/png", "image/jpeg", "image/gif", "text/markdown", "text/plain"}) {
            MultipartFile f = file(type, 10, "f");
            when(f.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[10]));
            when(storage.upload(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn("k");

            assertThat(service.uploadToPage(f, KB_ID, PAGE_ID, TENANT)).isEqualTo("k");
        }
    }

    @Test
    void getDownloadUrl_enabled_returnsPresignedUrl() {
        when(storage.isEnabled()).thenReturn(true);
        when(storage.presignedDownloadUrl("key")).thenReturn("https://signed/url");

        assertThat(service.getDownloadUrl("key")).isEqualTo("https://signed/url");
    }

    // ── 存储异常降级（500） ──────────────────────────────────
    @Test
    void uploadToPage_storageThrows_throws500() throws Exception {
        when(storage.isEnabled()).thenReturn(true);
        MultipartFile f = file("application/pdf", 10, "a.pdf");
        when(f.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[10]));
        when(storage.upload(anyString(), anyString(), anyString(), anyLong(), any()))
                .thenThrow(new RuntimeException("minio down"));

        assertThatThrownBy(() -> service.uploadToPage(f, KB_ID, PAGE_ID, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }
}
