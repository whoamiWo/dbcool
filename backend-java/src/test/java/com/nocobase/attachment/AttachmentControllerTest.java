package com.nocobase.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ResponseStatusException;

/**
 * AttachmentController 单元测试(Week 41 D1.2).
 *
 * <p>覆盖 metadata 注册 + Week 41 暂未实现的 get endpoint。
 */
class AttachmentControllerTest {

    private AttachmentController controller;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        // Week 41 复核 D1.4:Controller 现在依赖存储服务。
        // mock 的 isEnabled() 默认 false → upload / download 仍返 501,行为与改造前一致。
        controller = new AttachmentController(mock(MinioStorageService.class));
        user = new AuthenticatedUser(UUID.randomUUID(), "alice", "tenant_default");
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(user, "n/a",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        // 设置 TenantContext(模拟 JWT 过滤器已跑)
        com.nocobase.tenant.TenantContext.set("tenant_default");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        com.nocobase.tenant.TenantContext.clear();
    }

    @Test
    void createMetadata_valid_returnsRegistered() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storageKey", "orders/2026/x.pdf");
        body.put("originalName", "x.pdf");
        body.put("contentType", "application/pdf");
        body.put("size", 1024);

        Map<String, Object> resp = controller.createMetadata(body, user);

        assertThat(resp.get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data.get("storageKey")).isEqualTo("orders/2026/x.pdf");
        assertThat(data.get("tenantId")).isEqualTo("tenant_default");
    }

    @Test
    void createMetadata_missingStorageKey_returns400() {
        Map<String, Object> body = Map.of("originalName", "x.pdf");

        assertThatThrownBy(() -> controller.createMetadata(body, user))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void createMetadata_nullBody_returns400() {
        assertThatThrownBy(() -> controller.createMetadata(null, user))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void getMetadata_week41NotImplemented_returns501() {
        // Week 42+ D1.4 MinIO 集成后才实现
        assertThatThrownBy(() -> controller.getMetadata("some-key"))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_IMPLEMENTED);
    }
}
