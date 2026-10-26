package com.nocobase.integration.wechat;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.integration.common.ExternalUserMappingEntity;
import com.nocobase.integration.common.ExternalUserMappingRepository;
import com.nocobase.integration.credential.TenantCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PHASE B B2: WeChatAppService 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>isConfigured / getAppId / getAppSecret</li>
 *   <li>loginFromWeChat: 已有映射直接取本地用户（幂等）</li>
 *   <li>loginFromWeChat: 无映射但本地用户已存在 → 补建映射</li>
 *   <li>loginFromWeChat: 全新 openid → 建号 + 建映射</li>
 *   <li>loginFromWeChat: 未配置时抛异常</li>
 *   <li>loginFromWeChat: OAuth 响应缺 openid 时抛异常</li>
 *   <li>loginFromMiniProgram: 全新 openid → 建号 + 建映射</li>
 *   <li>loginFromMiniProgram: openid 缺失时抛异常</li>
 * </ul>
 */
class WeChatAppServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private TenantCredentialService credentialService;
    @Mock
    private ExternalUserMappingRepository mappingRepository;

    private WeChatAppService service;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new WeChatAppService(userRepository, passwordEncoder, restTemplate,
                credentialService, mappingRepository);
        setField(service, "appId", "wx_test_appid");
        setField(service, "appSecret", "wx_test_secret");
    }

    private static void setField(Object target, String name, String value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ---------- isConfigured / getters ----------

    @Test
    void isConfigured_returnsTrueWhenAppIdAndSecretPresent() {
        assertThat(service.isConfigured()).isTrue();
        assertThat(service.getAppId()).isEqualTo("wx_test_appid");
        assertThat(service.getAppSecret()).isEqualTo("wx_test_secret");
    }

    @Test
    void isConfigured_returnsFalseWhenNotSet() throws Exception {
        setField(service, "appId", "");
        setField(service, "appSecret", "");
        assertThat(service.isConfigured()).isFalse();
    }

    // ---------- loginFromWeChat ----------

    private static final String OPENID = "oTEST_OPENID_1234";
    /** safeTail(OPENID,12)：先去非字母数字 → "oTESTOPENID1234"，再取末 12 位 */
    private static final String TAIL12 = "STOPENID1234";

    private void mockOAuthResponse() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"access_token\":\"OAUTH_AT\",\"openid\":\"" + OPENID + "\"}")
                .thenReturn("{\"nickname\":\"张三\",\"unionid\":\"\"}");
    }

    @Test
    void loginFromWeChat_returnsExistingUserWhenMappingExists() {
        mockOAuthResponse();
        UUID localUserId = UUID.randomUUID();
        ExternalUserMappingEntity mapping = new ExternalUserMappingEntity();
        mapping.setLocalUserId(localUserId);
        when(mappingRepository.findByPlatformAndExternalUserIdAndTenantId(
                eq("wechat"), eq(OPENID), eq("system"))).thenReturn(Optional.of(mapping));
        UserEntity localUser = new UserEntity();
        localUser.setId(localUserId);
        localUser.setUsername("wechat_old");
        when(userRepository.findById(localUserId)).thenReturn(Optional.of(localUser));

        UserEntity result = service.loginFromWeChat("some_code");

        assertThat(result).isSameAs(localUser);
        verify(userRepository, never()).save(any(UserEntity.class));
    }

    @Test
    void loginFromWeChat_backfillsMappingWhenUserExistsByUsername() {
        mockOAuthResponse();
        when(mappingRepository.findByPlatformAndExternalUserIdAndTenantId(
                anyString(), eq(OPENID), anyString())).thenReturn(Optional.empty());
        when(userRepository.findByUsername("wechat_" + TAIL12))
                .thenAnswer(inv -> Optional.of(buildExisting()));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");

        UserEntity result = service.loginFromWeChat("some_code");

        assertThat(result).isNotNull();
        ArgumentCaptor<ExternalUserMappingEntity> cap =
                ArgumentCaptor.forClass(ExternalUserMappingEntity.class);
        verify(mappingRepository).save(cap.capture());
        assertThat(cap.getValue().getExternalUserId()).isEqualTo(OPENID);
        assertThat(cap.getValue().getPlatform()).isEqualTo("wechat");
    }

    @Test
    void loginFromWeChat_createsUserAndMappingWhenNoExisting() {
        mockOAuthResponse();
        when(mappingRepository.findByPlatformAndExternalUserIdAndTenantId(
                anyString(), eq(OPENID), anyString())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity result = service.loginFromWeChat("some_code");

        assertThat(result.getUsername()).isEqualTo("wechat_" + TAIL12);
        assertThat(result.getDisplayName()).isEqualTo("张三");
        assertThat(result.isEnabled()).isTrue();
        verify(mappingRepository).save(any(ExternalUserMappingEntity.class));
    }

    @Test
    void loginFromWeChat_throwsWhenNotConfigured() throws Exception {
        setField(service, "appId", "");
        setField(service, "appSecret", "");
        assertThatThrownBy(() -> service.loginFromWeChat("code"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    void loginFromWeChat_throwsWhenOAuthResponseMissingOpenid() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"errcode\":40029,\"errmsg\":\"invalid code\"}");
        assertThatThrownBy(() -> service.loginFromWeChat("bad_code"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("access_token/openid 失败");
    }

    // ---------- loginFromMiniProgram ----------

    @Test
    void loginFromMiniProgram_createsUserAndMapping() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"openid\":\"" + OPENID + "\",\"session_key\":\"SK\",\"unionid\":\"\"}");
        when(mappingRepository.findByPlatformAndExternalUserIdAndTenantId(
                anyString(), eq(OPENID), anyString())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity result = service.loginFromMiniProgram("js_code_abc");

        assertThat(result.getDisplayName()).isEqualTo("微信用户");
        assertThat(result.getUsername()).isEqualTo("wechat_" + TAIL12);
        verify(mappingRepository).save(any(ExternalUserMappingEntity.class));
    }

    @Test
    void loginFromMiniProgram_throwsWhenOpenidMissing() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"errcode\":40029,\"errmsg\":\"code is not exist\"}");
        assertThatThrownBy(() -> service.loginFromMiniProgram("bad_js_code"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("openid 失败");
    }

    private UserEntity buildExisting() {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername("wechat_" + TAIL12);
        u.setTenantId("system");
        u.setDisplayName("张三");
        u.setEnabled(true);
        return u;
    }
}
