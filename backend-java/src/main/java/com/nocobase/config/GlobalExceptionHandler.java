package com.nocobase.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理 — 把业务异常转成统一 JSON 格式,避免 Spring Security 拦截.
 *
 * <p>Week 8 修复:ExceptionTranslationFilter 之前会把所有 ResponseStatusException
 * 错误地翻译成 401. 现在 @ControllerAdvice 接管,Spring Security 只处理
 * 真正的 AuthenticationException / AccessDeniedException.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        Map<String, Object> body = Map.of(
                "code", e.getStatusCode().value(),
                "message", e.getReason() != null ? e.getReason() : "error",
                "data", Map.of()
        );
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIAE(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", 1000,
                "message", e.getMessage() != null ? e.getMessage() : "参数无效",
                "data", Map.of()
        ));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSec(SecurityException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "code", 1001,
                "message", e.getMessage() != null ? e.getMessage() : "未认证",
                "data", Map.of()
        ));
    }

    /**
     * Bean Validation 失败(比如 @Pattern 不匹配)→ 400 而不是 1001.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", 1000,
                "message", msg.isEmpty() ? "参数校验失败" : msg,
                "data", Map.of()
        ));
    }

    /**
     * JSON 解析失败 → 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", 1000,
                "message", "请求体格式错误",
                "data", Map.of()
        ));
    }

    /**
     * Map.of 不可变、或其他运行时错误 → 500 但转成 JSON.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", 500,
                "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                "data", Map.of()
        ));
    }
}

