package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class DingTalkAppServiceTest {

    private UserRepository userRepo;
    private PasswordEncoder passwordEncoder;
    private DingTalkAppService service;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenAnswer(inv -> "encoded_" + inv.getArgument(0));
        service = new DingTalkAppService(userRepo, passwordEncoder);
        // Set app-key/secret via reflection to enable isConfigured()
        setField(service, "appKey", "testAppKey");
        setField(service, "appSecret", "testAppSecret");
        setField(service, "agentId", "testAgentId");
        setField(service, "redirectUri", "https://example.com/callback");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = DingTalkAppService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String tid = "tenant_default";

    // ============================================================
    //  1. isConfigured
    // ============================================================

    @Test
    void isConfigured_returnsTrueWhenCredentialsSet() {
        assertThat(service.isConfigured()).isTrue();
    }

    // ============================================================
    //  2. getAuthUrl
    // ============================================================

    @Test
    void getAuthUrl_buildsCorrectUrl() {
        String url = service.getAuthUrl("state123", tid);
        assertThat(url).contains("client_id=testAppKey");
        assertThat(url).contains("redirect_uri=https://example.com/callback");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("scope=openid");
        assertThat(url).contains("state=state123");
        assertThat(url).contains("connect_id_type=unionid");
    }

    // ============================================================
    //  3. getAppConfig
    // ============================================================

    @Test
    void getAppConfig_returnsMaskedConfig() {
        Map<String, Object> cfg = service.getAppConfig();
        assertThat(cfg.get("appKey")).isEqualTo("testAppKey");
        assertThat(cfg.get("agentId")).isEqualTo("testAgentId");
        assertThat(cfg.get("redirectUri")).isEqualTo("https://example.com/callback");
        assertThat(cfg.get("configured")).isEqualTo(true);
    }

    // ============================================================
    //  4. syncUserFromDingTalk (new user)
    // ============================================================

    @Test
    void syncUserFromDingTalk_newUser_createsAccount() {
        when(userRepo.findByUsername("dt_abc123456789")).thenReturn(Optional.empty());
        when(userRepo.save(any())).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        UserEntity user = service.syncUserFromDingTalk("abc-123-XYZ", "张三", tid);
        assertThat(user.getUsername()).startsWith("dt_");
        assertThat(user.getDisplayName()).isEqualTo("张三");
        assertThat(user.getTenantId()).isEqualTo(tid);
        assertThat(user.isEnabled()).isTrue();
    }

    // ============================================================
    //  5. syncUserFromDingTalk (existing user — idempotent)
    // ============================================================

    @Test
    void syncUserFromDingTalk_existingUser_reusesAccount() {
        // safeTail("abc-123-XYZ") → "abc123XYZ" → username "dt_abc123XYZ"
        String expectedUsername = "dt_abc123XYZ";
        UserEntity existing = new UserEntity();
        existing.setId(UUID.randomUUID());
        existing.setUsername(expectedUsername);
        existing.setDisplayName("张三");
        existing.setTenantId(tid);
        when(userRepo.findByUsername(expectedUsername)).thenReturn(Optional.of(existing));

        UserEntity result = service.syncUserFromDingTalk("abc-123-XYZ", "张三", tid);
        assertThat(result).isSameAs(existing);
    }

    // ============================================================
    //  6. syncOrganization (not configured → error)
    // ============================================================

    @Test
    void syncOrganization_notConfigured_returnsError() {
        setField(service, "appKey", "");
        setField(service, "appSecret", "");
        Map<String, Object> result = service.syncOrganization(tid);
        assertThat(result.get("code")).isEqualTo(500);
        assertThat((String) result.get("message")).contains("未配置");
    }

    // ============================================================
    //  7. syncOrganization (configured → returns info)
    // ============================================================

    @Test
    void syncOrganization_configured_returnsSuccess() {
        Map<String, Object> result = service.syncOrganization(tid);
        assertThat(result.get("code")).isEqualTo(0);
        assertThat((String) result.get("message")).contains("已触发");
    }

    // ============================================================
    //  8. safeTail utility
    // ============================================================

    @Test
    void safeTail_stripsNonAlphanumeric() {
        // safeTail is private; test via syncUserFromDingTalk which uses it
        when(userRepo.findByUsername(any())).thenReturn(Optional.empty());
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // unionid with special chars → username should be alphanumeric only
        UserEntity user = service.syncUserFromDingTalk("unionid@special#123", "Test", tid);
        assertThat(user.getUsername()).matches("^dt_[a-zA-Z0-9]+$");
    }
}