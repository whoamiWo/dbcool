package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
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
}
