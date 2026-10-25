package com.nocobase.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.meta.CollectionService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE10: 公开表单（免登录填写）测试。
 *
 * <p>覆盖：
 * 1. share 生成 token（幂等：已存在时不重复生成）
 * 2. regenerate=true 强制轮换
 * 3. unshare 撤销（token 置 null）
 * 4. getByShareToken 命中 / 无效 404
 * 5. submitPublic 匿名提交落库 + TenantContext 恢复
 * 6. 空数据提交 → 400
 */
class FormPublicSubmitTest {

    private FormRepository repository;
    private CollectionService collectionService;
    private FormService service;

    private static final String TENANT = "tenant_public";
    private static final String COLLECTION = "customer";

    @BeforeEach
    void setUp() {
        repository = mock(FormRepository.class);
        collectionService = mock(CollectionService.class);
        service = new FormService(repository, new ObjectMapper(), collectionService);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearTenant() {
        try {
            com.nocobase.tenant.TenantContext.clear();
        } catch (Exception ignored) {
            // 忽略清理异常
        }
    }

    private FormEntity formWith(UUID id, String tenant, String token) {
        FormEntity f = new FormEntity();
        f.setId(id);
        f.setTenantId(tenant);
        f.setCollectionName(COLLECTION);
        f.setTitle("客户登记");
        f.setLayoutJson("[]");
        f.setRulesJson("{}");
        f.setShareToken(token);
        return f;
    }

    @Test
    void share_generatesToken_whenAbsent() {
        UUID id = UUID.randomUUID();
        FormEntity form = formWith(id, TENANT, null);
        when(repository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(form));

        FormEntity shared = service.share(id, TENANT, false);

        assertNotNull(shared.getShareToken());
        assertEquals(32, shared.getShareToken().length());
    }

    @Test
    void share_isIdempotent_whenTokenExists() {
        UUID id = UUID.randomUUID();
        String existing = "existing-token-aaaaaaaaaaaaaaaaaa";
        FormEntity form = formWith(id, TENANT, existing);
        when(repository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(form));

        FormEntity shared = service.share(id, TENANT, false);

        assertEquals(existing, shared.getShareToken());
    }

    @Test
    void share_regeneratesToken_whenForced() {
        UUID id = UUID.randomUUID();
        String original = "original-token-bbbbbbbbbbbbbbbb";
        FormEntity form = formWith(id, TENANT, original);
        when(repository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(form));

        FormEntity rotated = service.share(id, TENANT, true);

        assertNotNull(rotated.getShareToken());
        assertEquals(32, rotated.getShareToken().length());
    }

    @Test
    void unshare_clearsToken() {
        UUID id = UUID.randomUUID();
        FormEntity form = formWith(id, TENANT, "to-be-revoked-cccccccccccc");
        when(repository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(form));

        FormEntity revoked = service.unshare(id, TENANT);

        assertNull(revoked.getShareToken());
    }

    @Test
    void getByShareToken_returnsForm_whenValid() {
        UUID id = UUID.randomUUID();
        FormEntity form = formWith(id, TENANT, "valid-token-dddddddddddddd");
        when(repository.findByShareToken("valid-token-dddddddddddddd")).thenReturn(Optional.of(form));

        FormEntity found = service.getByShareToken("valid-token-dddddddddddddd");

        assertEquals(id, found.getId());
        assertEquals(TENANT, found.getTenantId());
    }

    @Test
    void getByShareToken_throws404_whenInvalid() {
        when(repository.findByShareToken(anyString())).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getByShareToken("nonexistent"));

        // 必须是 404（非 403），不泄露表单是否存在
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void getByShareToken_throws404_whenBlank() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getByShareToken("   "));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void submitPublic_insertsRecord_andRestoresTenantContext() {
        UUID id = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        FormEntity form = formWith(id, TENANT, "submit-token-eeeeeeeeeeee");
        when(repository.findByShareToken("submit-token-eeeeeeeeeeee")).thenReturn(Optional.of(form));
        when(collectionService.insertRecord(eq(COLLECTION), any(Map.class), eq(TENANT)))
                .thenReturn(recordId);

        Map<String, Object> data = new HashMap<>();
        data.put("name", "张三");
        UUID result = service.submitPublic("submit-token-eeeeeeeeeeee", data);

        assertEquals(recordId, result);
        // 提交后 TenantContext 必须回到"未设置"状态（currentTenantId 返回默认租户），
        // 绝不能残留表单所属租户 TENANT —— 否则线程复用会让后续请求串租户。
        String after = com.nocobase.tenant.TenantContext.currentTenantId();
        org.junit.jupiter.api.Assertions.assertNotEquals(TENANT, after);
        assertEquals(com.nocobase.tenant.TenantContext.DEFAULT_TENANT, after);
    }

    @Test
    void submitPublic_throws400_whenDataEmpty() {
        UUID id = UUID.randomUUID();
        FormEntity form = formWith(id, TENANT, "empty-token-ffffffffffff");
        when(repository.findByShareToken("empty-token-ffffffffffff")).thenReturn(Optional.of(form));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.submitPublic("empty-token-ffffffffffff", new HashMap<>()));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void submitPublic_throws404_whenTokenInvalid() {
        when(repository.findByShareToken(anyString())).thenReturn(Optional.empty());

        Map<String, Object> data = new HashMap<>();
        data.put("name", "李四");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.submitPublic("bad-token", data));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
