package com.nocobase.integration.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PHASE99 T1 专项测试：{@link CredentialCryptoService} 加解密往返 + 错误密钥失败。
 *
 * <p>验证红线 1 落地：
 * <ul>
 *   <li>密钥为空时启动即失败（由 {@code @PostConstruct validate()} 保证，已在 {@code TenantCredentialServiceTest} 验证）；</li>
 *   <li>同一密钥加密→解密往返等价；</li>
 *   <li>不同密钥解密同一密文会失败；</li>
 *   <li>非法 Base64 会抛出 {@link IllegalStateException}。</li>
 * </ul>
 */
class CredentialCryptoServiceTest {

    // 测试用 32 字节（Base64 编码后 44 字符）密钥，与 application-test.properties 一致
    private static final String TEST_KEY_B64 = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";

    private CredentialCryptoService cryptoA;
    private CredentialCryptoService cryptoB;

    @BeforeEach
    void setUp() {
        cryptoA = new CredentialCryptoService();
        cryptoA.injectKeyForTest(TEST_KEY_B64);

        cryptoB = new CredentialCryptoService();
        cryptoB.injectKeyForTest(TEST_KEY_B64);
    }

    @Test
    @DisplayName("同一密钥加解密往返等价")
    void encryptDecrypt_roundtrip() {
        for (String input : List.of(
                "",
                "short",
                "a".repeat(1000),
                "{\"app_id\":\"abc\",\"app_secret\":\"s3cr3t!@#\"}",
                "\u4e2d\u6587 unicode 🚀 emoji"
        )) {
            String encrypted = cryptoA.encrypt(input);
            assertThat(encrypted).isNotBlank();
            // 两次加密输出不同（GCM IV 随机）
            String encrypted2 = cryptoA.encrypt(input);
            assertThat(encrypted2).isNotEqualTo(encrypted);

            String decrypted = cryptoA.decrypt(encrypted);
            assertThat(decrypted).isEqualTo(input);
        }
    }

    @Test
    @DisplayName("不同密钥解同一密文抛异常")
    void decryptWithWrongKey_throws() {
        String plain = "secret";
        String encrypted = cryptoA.encrypt(plain);
        // 另一个服务用**合法的 32 字节**不同密钥（长度校验通过，但密钥不符）
        CredentialCryptoService cryptoWrong = new CredentialCryptoService();
        cryptoWrong.injectKeyForTest("OTk5OTk5OTk5OTk5OTk5OTk5OTk5OTk5OTk5OTk5OTk="); // 32 字节全 9
        assertThatThrownBy(() -> cryptoWrong.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("凭证解密失败");
    }

    @Test
    @DisplayName("注入 null/空字符串的密文抛异常")
    void decrypt_withNullOrEmpty_throws() {
        // null：会被包装成 IllegalStateException（底层 NPE）
        assertThatThrownBy(() -> cryptoA.decrypt(null))
                .isInstanceOf(IllegalStateException.class);
        // 空串：长度不足（< IV[12] + tag[16]）
        assertThatThrownBy(() -> cryptoA.decrypt(""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("注入 null 明文抛异常")
    void encrypt_null_throws() {
        assertThatThrownBy(() -> cryptoA.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能加密 null");
    }
}
