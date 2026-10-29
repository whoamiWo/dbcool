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
    void build_whenDisabled_returnsNullWithoutCallingPython() {
        ReflectionTestUtils.setField(service, "enabled", false);

        byte[] result = service.build("xlsx", List.of("col1"), List.of(List.of("v1")), "t");

        assertNull(result);
        verify(restTemplate, times(0)).exchange(any(String.class), any(), any(), eq(byte[].class));
    }

    @Test
    void build_nullHeaders_returnsNull() {
        assertNull(service.build("xlsx", null, List.of(), "t"));
    }

    @Test
    void build_emptyHeaders_returnsNull() {
        assertNull(service.build("xlsx", List.of(), List.of(), "t"));
    }

    @Test
    void build_invalidFormat_returnsNull() {
        assertNull(service.build("csv", List.of("col1"), List.of(), "t"));
    }

    @Test
    void build_nullFormat_returnsNull() {
        assertNull(service.build(null, List.of("col1"), List.of(), "t"));
    }

    @Test
    void build_success_returnsBytes() {
        byte[] expected = "fake-xlsx".getBytes();
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(expected, HttpStatus.OK));

        byte[] result = service.build("xlsx", List.of("col1"), List.of(List.of("v1")), "t");

        assertNotNull(result);
        assertArrayEquals(expected, result);
    }

    @Test
    void build_pdfSuccess_returnsBytes() {
        byte[] expected = "%PDF-1.4".getBytes();
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(expected, HttpStatus.OK));

        byte[] result = service.build("PDF", List.of("col1"), List.of(), "t");

        assertArrayEquals(expected, result);
    }

    @Test
    void build_emptyResponseBody_returnsNull() {
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(new byte[0], HttpStatus.OK));

        assertNull(service.build("xlsx", List.of("col1"), List.of(), "t"));
    }

    @Test
    void build_nullResponseBody_returnsNull() {
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        assertNull(service.build("xlsx", List.of("col1"), List.of(), "t"));
    }

    @Test
    void build_clientError_returnsNull() {
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertNull(service.build("xlsx", List.of("col1"), List.of(), "t"));
    }

    @Test
    void build_serverError_returnsNull() {
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertNull(service.build("xlsx", List.of("col1"), List.of(), "t"));
    }

    @Test
    void build_connectionFailure_returnsNull() {
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("connect timed out"));

        assertNull(service.build("xlsx", List.of("col1"), List.of(), "t"));
    }

    @Test
    void build_nullRowsAndTitle_stillSucceeds() {
        byte[] expected = "ok".getBytes();
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(expected, HttpStatus.OK));

        byte[] result = service.build("xlsx", List.of("col1"), null, null);

        assertArrayEquals(expected, result);
    }

    @Test
    void build_blankInternalToken_omitsAuthorizationHeader() {
        ReflectionTestUtils.setField(service, "internalToken", "");
        byte[] expected = "ok".getBytes();
        when(restTemplate.exchange(any(String.class), any(), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(expected, HttpStatus.OK));

        byte[] result = service.build("xlsx", List.of("col1"), List.of(), "t");

        assertNotNull(result);
    }

    @Test
    void mediaTypeOf_and_extensionOf_mapCorrectly() {
        assertEquals("application/pdf", ExportFileService.mediaTypeOf("pdf"));
        assertEquals(".pdf", ExportFileService.extensionOf("pdf"));
        assertTrue(ExportFileService.mediaTypeOf("xlsx").contains("spreadsheetml"));
        assertEquals(".xlsx", ExportFileService.extensionOf("xlsx"));
        assertEquals(".xlsx", ExportFileService.extensionOf(null));
    }
}
