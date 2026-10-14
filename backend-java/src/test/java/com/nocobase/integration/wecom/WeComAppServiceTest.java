package com.nocobase.integration.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.integration.credential.TenantCredentialService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;

/**
 * 企业微信应用服务测试 — 覆盖登录回调与配置守卫。
 */
class WeComAppServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private RestTemplate restTemplate;
    private WeComAppService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "encoded_" + inv.getArgument(0));
        restTemplate = mock(RestTemplate.class);
        service = new WeComAppService(userRepository, passwordEncoder, restTemplate,
                mock(TenantCredentialService.class));
        setField(service, "corpId", "testCorpId");
        setField(service, "agentId", "12345");
        setField(service, "secret", "testSecret");
        setField(service, "redirectUri", "https://example.com/callback");
        // 缓存 token
        setField(service, "cachedToken", "cached_access_token");
        setField(service, "cachedTokenExpireAt", System.currentTimeMillis() + 600000);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = WeComAppService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(kv[i]).append("\":\"").append(kv[i + 1]).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    @Test
    void isConfigured_returnsTrueWhenCredentialsSet() {
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void getCorpId_and_getAgentId_returnConfiguredValues() {
        assertThat(service.getCorpId()).isEqualTo("testCorpId");
        assertThat(service.getAgentId()).isEqualTo("12345");
    }

    @Test
    void loginFromWeCom_newUser_createsAccount() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(json("userid", "zhangsan"))
                .thenReturn(json("name", "张三", "email", "zhang@example.com"));

        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        UserEntity user = service.loginFromWeCom("valid_code_123");
        assertThat(user).isNotNull();
        assertThat(user.getUsername()).startsWith("wecom_");
        assertThat(user.getDisplayName()).isEqualTo("张三");
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void loginFromWeCom_existingUser_returnsExisting() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(json("userid", "zhangsan"))
                .thenReturn(json("name", "张三", "email", "zhang@example.com"));

        UserEntity existing = new UserEntity();
        existing.setId(UUID.randomUUID());
        existing.setUsername("wecom_zhangsan");
        existing.setDisplayName("张三（旧）");
        when(userRepository.findByUsername("wecom_zhangsan")).thenReturn(Optional.of(existing));

        UserEntity user = service.loginFromWeCom("valid_code_123");
        assertThat(user).isSameAs(existing); // 幂等：不重复建号
    }

    @Test
    void loginFromWeCom_emptyUserid_throws() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(json("errcode", "40029"))
                .thenReturn(json());

        assertThatCode(() -> service.loginFromWeCom("bad_code"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("userid");
    }

    @Test
    void loginFromWeCom_nullBody_throws() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(null)
                .thenReturn(null);

        assertThatCode(() -> service.loginFromWeCom("code_x"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void getAccessToken_cacheExpiry_refetchesToken() throws Exception {
        // 让缓存过期，模拟一次真实的 token 请求
        setField(service, "cachedTokenExpireAt", System.currentTimeMillis() - 1);
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(json("access_token", "fresh_token"));

        String token = service.getAccessToken();
        assertThat(token).isEqualTo("fresh_token");

        // 第二次调用应命中缓存
        String again = service.getAccessToken();
        assertThat(again).isEqualTo("fresh_token");
        verify(restTemplate, org.mockito.Mockito.times(1)).getForObject(anyString(), eq(String.class));
    }

    @Test
    void getAccessToken_missingConfig_throws() {
        setField(service, "corpId", "");
        setField(service, "cachedToken", null);
        assertThatCode(() -> service.getAccessToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corp-id");
    }

    @Test
    void syncContacts_createssUsers_whenNew() {
        // 部门列表 → 成员列表 → 建号
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"department\":[{\"id\":\"1\",\"name\":\"总部\"},{\"id\":\"2\",\"name\":\"研发\"}]}")
                .thenReturn("{\"userlist\":[{\"userid\":\"lisi\",\"name\":\"李四\",\"email\":\"lisi@example.com\"}]}");

        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.syncContacts();
        assertThat(result.get("code")).isEqualTo(0);
        assertThat(result.get("departments")).isEqualTo(2);
        assertThat(result.get("users")).isEqualTo(1);
    }

    @Test
    void syncContacts_skipsExistingUsers() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"department\":[{\"id\":\"1\",\"name\":\"总部\"}]}")
                .thenReturn("{\"userlist\":[{\"userid\":\"lisi\",\"name\":\"李四\",\"email\":\"lisi@example.com\"}]}");

        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByEmail("lisi@example.com")).thenReturn(Optional.of(new UserEntity()));

        var result = service.syncContacts();
        assertThat(result.get("users")).isEqualTo(0); // 已存在，跳过
    }

    @Test
    void syncContacts_emptyDepartment_zeroUsers() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"department\":[]}");

        var result = service.syncContacts();
        assertThat(result.get("departments")).isEqualTo(0);
        assertThat(result.get("users")).isEqualTo(0);
    }

    @Test
    void syncContacts_notConfigured_throws() {
        setField(service, "corpId", "");
        assertThatCode(() -> service.syncContacts())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
    }
}