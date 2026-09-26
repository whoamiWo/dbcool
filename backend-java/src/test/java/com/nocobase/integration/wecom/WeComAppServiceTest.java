package com.nocobase.integration.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
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
        service = new WeComAppService(userRepository, passwordEncoder, restTemplate);
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
        when(restTemplate.getForObject(anyString(), String.class))
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
}