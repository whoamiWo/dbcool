package com.nocobase.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
 * AttachmentController 单元测试 (Week 41 D1.2 + PHASE69 R2).
 */
class AttachmentControllerTest {

    private AttachmentController controller;
    private MinioStorageService storage;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        storage = mock(MinioStorageService.class);
        when(storage.isEnabled()).thenReturn(true);
        when(storage.presignedDownloadUrl(anyString())).thenReturn("http://minio/pre-signed-url");
        controller = new AttachmentController(storage);
        user = new AuthenticatedUser(UUID.randomUUID(), "alice", "tenant_default");
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(user, "n/a",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")))));
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

    // PHASE69 R2: getMetadata with tenant validation
    @Test
    void getMetadata_validStorageKey_returnsOk() {
        var resp = controller.getMetadata("tenant_default/file.txt");
        
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody();
        assertThat(data).containsKey("downloadUrl");
    }

    // PHASE69 R2: 租户校验测试
    @Test
    void download_sameTenant_returns302() {
        String storageKey = "tenant_default/file.txt";
        
        var resp = controller.download(storageKey);
        
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation()).isNotNull();
    }

    @Test
    void download_otherTenant_returns403() {
        String storageKey = "other_tenant/file.txt";
        
        assertThatThrownBy(() -> controller.download(storageKey))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN);
    }

    @Test
    void download_pathTraversal_returns400() {
        assertThatThrownBy(() -> controller.download("../etc/passwd"))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void download_backslash_returns400() {
        assertThatThrownBy(() -> controller.download("tenant_default\\..\\etc\\passwd"))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void download_absolutePath_returns400() {
        assertThatThrownBy(() -> controller.download("/etc/passwd"))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }
}
