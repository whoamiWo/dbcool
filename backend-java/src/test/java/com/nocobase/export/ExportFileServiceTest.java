package com.nocobase.export;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExportFileService} 单元测试。
 *
 * <p>直接构造实例并通过反射注入依赖，避免拉起 Spring 上下文
 * （本仓库 @SpringBootTest 在本地环境普遍失败，见环境基线）。
 *
 * <p>v2.1.4：build() 由「返回 null」改为「返回 Result（data / reason）」，
 * 测试相应断言**具体失败原因**（比仅断言 null 更强）——用于保证
 * 5003（依赖缺失，可 SKIP）与 5004（数据/契约缺陷，应 FAIL）不被混淆。
 */
@ExtendWith(MockitoExtension.class)
class ExportFileServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private ExportFileService service;

    @BeforeEach
    void setUp() {
        service = new ExportFileService();
        ReflectionTestUtils.setField(service, "rest", restTemplate);
        ReflectionTestUtils.setField(service, "pythonUrl", "http://localhost:8000");
        ReflectionTestUtils.setField(service, "internalToken", "test-token");
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @Test
    void build_whenDisabled_returnsDisabledReasonWithoutCallingPython() {
        ReflectionTestUtils.setField(service, "enabled", false);

        ExportFileService.Result result =
                service.build("xlsx", List.of("col1"), List.of(List.of("v1")), "t");

        assertFalse(result.isSuccess());
        assertEquals(ExportFailureReason.DISABLED_BY_CONFIG, result.reason());
        assertNull(result.data());
        verify(restTemplate, times(0)).exchange(any(String.class), any(), any(), eq(byte[].class));
    }

    @Test
    void build_nullHeaders_returnsEmptyHeadersReason() {
        ExportFileService.Result result = service.build("xlsx", null, List.of(), "t");

        assertFalse(result.isSuccess());
        assertEquals(ExportFailureReason.EMPTY_HEADERS, result.reason());
    }

    @Test
    void build_emptyHeaders_returnsEmptyHeadersReason() {
        ExportFileService.Result result = service.build("xlsx", List.of(), List.of(), "t");

        assertFalse(result.isSuccess());
        assertEquals(ExportFailureReason.EMPTY_HEADERS, result.reason());
    }

    @Test
    void build_invalidFormat_returnsUnsupportedFormatReason() {
        ExportFileService.Result result = service.build("csv", List.of("col1"), List.of(), "t");

        assertFalse(result.isSuccess());
        assertEquals(ExportFailureReason.UNSUPPORTED_FORMAT, result.reason());
    }

    @Test
    void build_nullFormat_returnsUnsupportedFormatReason() {
        ExportFileService.Result result = service.build(null, List.of("col1"), List.of(), "t");

        assertFalse(result.isSuccess());
        assertEquals(ExportFailureReason.UNSUPPORTED_FORMAT, result.reason());
    }

    @Test
    void build_success_returnsBytes() {
        byte[] expected = "fake-xlsx".getBytes();
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(expected, HttpStatus.OK));

        ExportFileService.Result result =
                service.build("xlsx", List.of("col1"), List.of(List.of("v1")), "t");

        assertTrue(result.isSuccess());
        assertNull(result.reason());
        assertNotNull(result.data());
        assertArrayEquals(expected, result.data());
    }

    @Test
    void build_pdfSuccess_returnsBytes() {
        byte[] expected = "%PDF-1.4".getBytes();
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(expected, HttpStatus.OK));

        ExportFileService.Result result = service.build("PDF", List.of("col1"), List.of(), "t");

        assertTrue(result.isSuccess());
        assertArrayEquals(expected, result.data());
    }

    @Test
    void build_emptyResponseBody_returnsEmptyContentReason() {
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[0], HttpStatus.OK));

        ExportFileService.Result result = service.build("xlsx", List.of("col1"), List.of(), "t");

        assertFalse(result.isSuccess());
        // Python 返回空 = Python 侧缺陷 → 5004（应 FAIL，不得当依赖缺失跳过）
        assertEquals(ExportFailureReason.EMPTY_CONTENT_FROM_PYTHON, result.reason());
    }

    @Test
    void build_nullResponseBody_returnsEmptyContentReason() {
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        ExportFileService.Result result = service.build("xlsx", List.of("col1"), List.of(), "t");

        assertFalse(result.isSuccess());
        assertEquals(ExportFailureReason.EMPTY_CONTENT_FROM_PYTHON, result.reason());
    }

    @Test
    void build_clientError_returnsServiceUnavailableReason() {
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        ExportFileService.Result result = service.build("xlsx", List.of("col1"), List.of(), "t");

        assertFalse(result.isSuccess());
        assertEquals(ExportFailureReason.SERVICE_UNAVAILABLE, result.reason());
    }

    @Test
    void build_serverError_returnsServiceUnavailableReason() {
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        ExportFileService.Result result = service.build("xlsx", List.of("col1"), List.of(), "t");

        assertFalse(result.isSuccess());
        assertEquals(ExportFailureReason.SERVICE_UNAVAILABLE, result.reason());
    }

    @Test
    void build_connectionFailure_returnsServiceUnavailableReason() {
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("connect timed out"));

        ExportFileService.Result result = service.build("xlsx", List.of("col1"), List.of(), "t");

        assertFalse(result.isSuccess());
        assertEquals(ExportFailureReason.SERVICE_UNAVAILABLE, result.reason());
    }

    @Test
    void build_nullRowsAndTitle_stillSucceeds() {
        byte[] expected = "ok".getBytes();
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(expected, HttpStatus.OK));

        ExportFileService.Result result = service.build("xlsx", List.of("col1"), null, null);

        assertTrue(result.isSuccess());
        assertArrayEquals(expected, result.data());
    }

    @Test
    void build_blankInternalToken_omitsAuthorizationHeader() {
        ReflectionTestUtils.setField(service, "internalToken", "");
        byte[] expected = "ok".getBytes();
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(expected, HttpStatus.OK));

        ExportFileService.Result result = service.build("xlsx", List.of("col1"), List.of(), "t");

        assertTrue(result.isSuccess());
        assertNotNull(result.data());
    }

    @Test
    void mediaTypeOf_and_extensionOf_mapCorrectly() {
        assertEquals("application/pdf", ExportFileService.mediaTypeOf("pdf"));
        assertEquals(".pdf", ExportFileService.extensionOf("pdf"));
        assertTrue(ExportFileService.mediaTypeOf("xlsx").contains("spreadsheetml"));
        assertEquals(".xlsx", ExportFileService.extensionOf("xlsx"));
        assertEquals(".xlsx", ExportFileService.extensionOf(null));
    }

    // ============ 失败原因分类契约（5003 vs 5004） ============

    @Test
    void failureReason_codes_splitInto503And500() {
        // 5003：服务不可用 / 配置关闭 → smoke 可 SKIP
        assertEquals(5003, ExportFailureReason.SERVICE_UNAVAILABLE.getCode());
        assertEquals(5003, ExportFailureReason.DISABLED_BY_CONFIG.getCode());
        // 5004：表头空 / 格式不支持 / Python 返空 → 真实缺陷，smoke 须 FAIL
        assertEquals(5004, ExportFailureReason.EMPTY_HEADERS.getCode());
        assertEquals(5004, ExportFailureReason.UNSUPPORTED_FORMAT.getCode());
        assertEquals(5004, ExportFailureReason.EMPTY_CONTENT_FROM_PYTHON.getCode());
    }
}
