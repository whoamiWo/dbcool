package com.nocobase.integration.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 飞书应用服务测试 — 覆盖签名校验正反向与配置守卫。
 */
class FeishuAppServiceTest {

    private FeishuAppService service;

    @BeforeEach
    void setUp() {
        service = new FeishuAppService();
        setField(service, "appId", "testAppId");
        setField(service, "appSecret", "testAppSecret");
        setField(service, "redirectUri", "https://example.com/callback");
        setField(service, "encryptKey", "testEncryptKey");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = FeishuAppService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    @Test
    void isConfigured_returnsTrueWhenCredentialsSet() {
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void getOAuthUrl_buildsCorrectUrl() {
        String url = service.getOAuthUrl();
        assertThat(url).contains("app_id=testAppId");
        assertThat(url).contains("redirect_uri=https://example.com/callback");
        assertThat(url).contains("scope=im:message:send_as_bot");
    }

    @Test
    void verifySignature_validSignature_accepted() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce123";
        String body = "{\"event\":\"x\"}";
        String signing = ts + nonce + "testEncryptKey" + body;
        String sig = sha256(signing);
        assertThat(service.verifySignature(ts, nonce, sig, body)).isTrue();
    }

    @Test
    void verifySignature_wrongSignature_rejected() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce123";
        String body = "{}";
        String signing = ts + nonce + "testEncryptKey" + body;
        String sig = sha256(signing) + "deadbeef";
        assertThat(service.verifySignature(ts, nonce, sig, body)).isFalse();
    }

    @Test
    void verifySignature_missingParams_rejected() {
        assertThat(service.verifySignature(null, "n", "s", "b")).isFalse();
        assertThat(service.verifySignature("t", null, "s", "b")).isFalse();
        assertThat(service.verifySignature("t", "n", null, "b")).isFalse();
    }

    @Test
    void verifySignature_tamperedBody_rejected() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce123";
        String body = "{\"event\":\"x\"}";
        String signing = ts + nonce + "testEncryptKey" + body;
        String sig = sha256(signing);
        // 改 body 后签名应失效
        assertThat(service.verifySignature(ts, nonce, sig, "{\"event\":\"y\"}")).isFalse();
    }
}