package com.nocobase.apikey;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PHASE105: ApiKeyController 测试 (list/create/revoke + 错误分支)。 */
class ApiKeyControllerTest {

    private ApiKeyService service;
    private AuditService auditService;
    private ApiKeyController controller;

    private static final String TENANT = "tenant_default";
    private static final AuthenticatedUser USER =
            new AuthenticatedUser(UUID.randomUUID(), "admin", TENANT);

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        service = mock(ApiKeyService.class);
        auditService = mock(AuditService.class);
        controller = new ApiKeyController(service, auditService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void list_returnsDtos() {
        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(UUID.randomUUID());
        e.setName("k1");
        e.setKeyPrefix("pk_");
        e.setScopes("read,write");
        e.setCreatedAt(java.time.Instant.now());
        when(service.listActive(TENANT)).thenReturn(List.of(e));

        Map<String, Object> resp = controller.list();

        assertThat(resp.get("code")).isEqualTo(0);
        List<?> data = (List<?>) resp.get("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(((Map<?, ?>) data.get(0)).get("name")).isEqualTo("k1");
    }

    @Test
    void create_blankName_throws400() {
        var req = new ApiKeyController.CreateKeyRequest("  ", "read", null);

        assertThatThrownBy(() -> controller.create(req, USER))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_success_returns201WithRawKey() {
        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(UUID.randomUUID());
        e.setName("k1");
        e.setKeyPrefix("pk_");
        e.setScopes("read");
        // 注意: 源码第 65 行 Map.of("expiresInDays", req.expiresInDays()) 不接受 null 值,
        // 故传 expiresInDays=30 覆盖正常路径(含 expiresAt 计算分支)。
        var created = new ApiKeyService.CreatedKey("RAW_KEY", e);
        when(service.create(eq("k1"), eq("read"), any(), eq(TENANT), eq(USER.userId())))
                .thenReturn(created);

        var req = new ApiKeyController.CreateKeyRequest("k1", "read", 30);
        ResponseEntity<Map<String, Object>> resp = controller.create(req, USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("rawKey")).isEqualTo("RAW_KEY");
        assertThat(data.get("keyPrefix")).isEqualTo("pk_");
        verify(auditService).log(eq(TENANT), eq(USER.userId()), eq("admin"),
                eq("apikey.create"), anyString(), anyString(), any(Map.class));
    }

    @Test
    void revoke_success_returnsOk() {
        UUID id = UUID.randomUUID();
        when(service.revoke(id, TENANT)).thenReturn(true);

        Map<String, Object> resp = controller.revoke(id, USER);

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(resp.get("message")).isEqualTo("revoked");
        verify(auditService).log(eq(TENANT), eq(USER.userId()), eq("admin"),
                eq("apikey.revoke"), anyString(), anyString(), any(Map.class));
    }

    @Test
    void revoke_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(service.revoke(id, TENANT)).thenReturn(false);

        assertThatThrownBy(() -> controller.revoke(id, USER))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }
}
