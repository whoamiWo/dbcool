package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.UserRepository;
import com.nocobase.integration.credential.TenantCredentialService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** PHASE105: DingTalkAppService 测试。 */
class DingTalkAppServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private TenantCredentialService credentialService;
    private DingTalkAppService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        credentialService = mock(TenantCredentialService.class);
        service = new DingTalkAppService(userRepository, passwordEncoder, credentialService);
    }

    private void setField(String name, String value) throws Exception {
        Field f = DingTalkAppService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    @SuppressWarnings("unchecked")
    private Optional<String> resolveForKey(String key, String tenantId) throws Exception {
        Method m = DingTalkAppService.class.getDeclaredMethod("resolveForKey", String.class, String.class);
        m.setAccessible(true);
        return (Optional<String>) m.invoke(service, key, tenantId);
    }

    @Test
    void isConfigured_globalValuesPresent_returnsTrue() throws Exception {
        setField("appKey", "ak");
        setField("appSecret", "as");

        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void isConfigured_missingAppSecret_returnsFalse() throws Exception {
        setField("appKey", "ak");
        setField("appSecret", "");

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_nothingConfigured_returnsFalse() {
        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_tenantLevelOnly_returnsTrue() {
        when(credentialService.get("t1", "dingtalk", "app-key")).thenReturn(Optional.of("tk"));
        when(credentialService.get("t1", "dingtalk", "app-secret")).thenReturn(Optional.of("ts"));

        assertThat(service.isConfigured("t1")).isTrue();
    }

    @Test
    void isConfigured_tenantMissing_fallsBackToGlobal() throws Exception {
        setField("appKey", "gk");
        setField("appSecret", "gs");
        when(credentialService.get(eq("t1"), eq("dingtalk"), eq("app-key"))).thenReturn(Optional.empty());
        when(credentialService.get(eq("t1"), eq("dingtalk"), eq("app-secret"))).thenReturn(Optional.empty());

        assertThat(service.isConfigured("t1")).isTrue();
    }

    @Test
    void resolveForKey_tenantPriorityOverGlobal() throws Exception {
        setField("appKey", "global-key");
        when(credentialService.get("t1", "dingtalk", "app-key")).thenReturn(Optional.of("tenant-key"));

        assertThat(resolveForKey("app-key", "t1")).contains("tenant-key");
    }

    @Test
    void resolveForKey_nullTenant_usesGlobalOnly() throws Exception {
        setField("appKey", "global-key");

        assertThat(resolveForKey("app-key", null)).contains("global-key");
    }

    @Test
    void resolveForKey_blankTenant_usesGlobalOnly() throws Exception {
        setField("appKey", "global-key");

        assertThat(resolveForKey("app-key", "  ")).contains("global-key");
    }

    @Test
    void resolveForKey_unknownKey_returnsEmpty() throws Exception {
        assertThat(resolveForKey("nonexistent", null)).isEmpty();
    }

    @Test
    void resolveForKey_noGlobalNoTenant_returnsEmpty() throws Exception {
        assertThat(resolveForKey("app-key", "t1")).isEmpty();
    }

    @Test
    void exchangeCode_unconfigured_returns500() {
        when(credentialService.get(eq("t1"), eq("dingtalk"), eq("app-key"))).thenReturn(Optional.empty());
        when(credentialService.get(eq("t1"), eq("dingtalk"), eq("app-secret"))).thenReturn(Optional.empty());

        var resp = service.exchangeCode("code", "t1");
        assertThat(resp.get("code")).isEqualTo(500);
    }

    @Test
    void exchangeCode_emptyCode_returns400() throws Exception {
        setField("appKey", "ak");
        setField("appSecret", "as");

        var resp = service.exchangeCode("", "t1");
        assertThat(resp.get("code")).isEqualTo(400);
    }

    @Test
    void syncUserFromDingTalk_existingUser_returnsExisting() throws Exception {
        var existing = new com.nocobase.auth.UserEntity();
        existing.setId(java.util.UUID.randomUUID());
        existing.setUsername("dt_abc123");
        existing.setTenantId("t1");
        when(userRepository.findByUsername("dt_abc123")).thenReturn(java.util.Optional.of(existing));

        var user = service.syncUserFromDingTalk("abc123", "Nick", "t1");
        assertThat(user).isEqualTo(existing);
    }

    @Test
    void syncUserFromDingTalk_newUser_createsWithRandomPassword() throws Exception {
        when(userRepository.findByUsername("dt_xyz789")).thenReturn(java.util.Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var user = service.syncUserFromDingTalk("xyz789", "Display", "t1");
        assertThat(user.getUsername()).startsWith("dt_");
        assertThat(user.getDisplayName()).isEqualTo("Display");
        assertThat(user.getTenantId()).isEqualTo("t1");
    }

    @Test
    void getAppConfig_returnsConfigFields() throws Exception {
        setField("appKey", "mykey");
        setField("appSecret", "mysecret");
        setField("agentId", "agent-123");
        setField("redirectUri", "https://example.com/callback");

        var cfg = service.getAppConfig();
        assertThat(cfg.get("appKey")).isEqualTo("mykey");
        assertThat(cfg.get("agentId")).isEqualTo("agent-123");
        assertThat(cfg.get("redirectUri")).isEqualTo("https://example.com/callback");
        assertThat(cfg.get("configured")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void safeTail_keepsAlphanumericAndTruncatesToMax() throws Exception {
        Method m = DingTalkAppService.class.getDeclaredMethod("safeTail", String.class, int.class);
        m.setAccessible(true);

        // 非字母数字被剥离
        assertThat((String)m.invoke(null, "abc!@#def", 12)).isEqualTo("abcdef");
        // 空 → "unknown"
        assertThat((String)m.invoke(null, "", 12)).isEqualTo("unknown");
        // 短于 max 原样返回
        assertThat((String)m.invoke(null, "short", 10)).isEqualTo("short");
        // 长于 max 截取尾部 max 位
        assertThat((String)m.invoke(null, "abcdef", 3)).isEqualTo("def");
    }

    @Test
    void randomPassword_generatesBase64Url() throws Exception {
        Method m = DingTalkAppService.class.getDeclaredMethod("randomPassword");
        m.setAccessible(true);

        String pwd = (String) m.invoke(null);
        assertThat(pwd).matches("^[A-Za-z0-9_-]{32}$");
    }
}
