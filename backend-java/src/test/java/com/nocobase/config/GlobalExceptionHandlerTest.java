package com.nocobase.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

/**
 * GlobalExceptionHandler 单测(Week 30).
 * 测 6 个 ExceptionHandler:ResponseStatus / IAE / Security / Validation / NotReadable / Runtime.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleResponseStatus_usesStatusCodeAndReason() {
        ResponseStatusException e = new ResponseStatusException(HttpStatus.FORBIDDEN, "denied");
        ResponseEntity<Map<String, Object>> resp = handler.handleResponseStatus(e);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals(403, resp.getBody().get("code"));
        assertEquals("denied", resp.getBody().get("message"));
    }

    @Test
    void handleResponseStatus_nullReason_fallsBackToError() {
        ResponseStatusException e = new ResponseStatusException(HttpStatus.NOT_FOUND, null);
        ResponseEntity<Map<String, Object>> resp = handler.handleResponseStatus(e);
        assertEquals("error", resp.getBody().get("message"));
    }

    @Test
    void handleIAE_returns400WithCode1000() {
        ResponseEntity<Map<String, Object>> resp = handler.handleIAE(
                new IllegalArgumentException("bad name"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(1000, resp.getBody().get("code"));
        assertEquals("bad name", resp.getBody().get("message"));
    }

    @Test
    void handleIAE_nullMessage_fallsBackToDefault() {
        ResponseEntity<Map<String, Object>> resp = handler.handleIAE(
                new IllegalArgumentException());
        assertEquals("参数无效", resp.getBody().get("message"));
    }

    @Test
    void handleSec_returns401WithCode1001() {
        ResponseEntity<Map<String, Object>> resp = handler.handleSec(
                new SecurityException("no auth"));
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals(1001, resp.getBody().get("code"));
        assertEquals("no auth", resp.getBody().get("message"));
    }

    @Test
    void handleSec_nullMessage_fallsBackToDefault() {
        ResponseEntity<Map<String, Object>> resp = handler.handleSec(new SecurityException());
        assertEquals("未认证", resp.getBody().get("message"));
    }

    @Test
    void handleValidation_returns400WithFieldErrors() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "target");
        br.addError(new FieldError("target", "name", "must not be blank"));
        br.addError(new FieldError("target", "email", "must be a valid email"));
        MethodArgumentNotValidException e = new MethodArgumentNotValidException(null, br);

        ResponseEntity<Map<String, Object>> resp = handler.handleValidation(e);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(1000, resp.getBody().get("code"));
        String msg = (String) resp.getBody().get("message");
        assertTrue(msg.contains("name: must not be blank"));
        assertTrue(msg.contains("email: must be a valid email"));
    }

    @Test
    void handleValidation_noFieldErrors_returnsDefaultMessage() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "target");
        MethodArgumentNotValidException e = new MethodArgumentNotValidException(null, br);

        ResponseEntity<Map<String, Object>> resp = handler.handleValidation(e);
        assertEquals("参数校验失败", resp.getBody().get("message"));
    }

    @Test
    void handleNotReadable_returns400() {
        HttpMessageNotReadableException e = new HttpMessageNotReadableException("bad json");
        ResponseEntity<Map<String, Object>> resp = handler.handleNotReadable(e);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(1000, resp.getBody().get("code"));
        assertEquals("请求体格式错误", resp.getBody().get("message"));
    }

    @Test
    void handleRuntime_returns500() {
        ResponseEntity<Map<String, Object>> resp = handler.handleRuntime(
                new RuntimeException("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals(500, resp.getBody().get("code"));
        assertEquals("boom", resp.getBody().get("message"));
    }

    @Test
    void handleRuntime_nullMessage_fallsBackToClassName() {
        ResponseEntity<Map<String, Object>> resp = handler.handleRuntime(new RuntimeException());
        assertEquals("RuntimeException", resp.getBody().get("message"));
    }
}