package com.nocobase.integration.credential;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PHASE99 T1: 集成凭证加解密服务（AES-GCM）。
 *
 * <p>红线 1：主密钥必须来自环境变量/配置（{@code integration.credential.master-key}，
 * Base64 编码的 32 字节密钥），**禁止硬编码**。未配置时**启动即失败**，
 * 避免"以为加密了实际是明文"的静默降级。
 *
 * <p>存储格式：{@code Base64(IV[12] || ciphertext || tag[16])}。
 * GCM 自带完整性校验，密文被篡改时解密直接抛异常而非返回错误明文。
 */
@Component
public class CredentialCryptoService {
    private static final Logger log = LoggerFactory.getLogger(CredentialCryptoService.class);

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Base64 编码的 32 字节主密钥；未配置则启动失败 */
    @Value("${integration.credential.master-key:}")
    private String masterKeyBase64;

    private SecretKeySpec keySpec;

    @PostConstruct
    void validate() {
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            throw new IllegalStateException(
                "PHASE99 红线：integration.credential.master-key 未配置。" +
                "请设置 32 字节 Base64 密钥（openssl rand -base64 32），禁止硬编码。");
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalStateException(
                    "integration.credential.master-key 必须解码为 32 字节（AES-256），当前 " + keyBytes.length + " 字节");
            }
            this.keySpec = new SecretKeySpec(keyBytes, "AES");
            log.info("[credential-crypto] AES-GCM 主密钥已加载（长度 32 字节）");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("integration.credential.master-key 不是合法 Base64: " + e.getMessage(), e);
        }
    }

    /** 加密明文，返回可落库的 Base64 密文 */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("不能加密 null");
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // 拼接 IV || ciphertext || tag（GCM 的 doFinal 输出已含 tag）
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("凭证加密失败", e);
        }
    }

    /** 解密密文；密文被篡改或密钥不符时抛异常（GCM 完整性校验） */
    public String decrypt(String combinedBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(combinedBase64);
            if (combined.length < GCM_IV_BYTES + 16) {
                throw new IllegalStateException("密文长度不足（至少 IV[12] + tag[16]）");
            }
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);
            byte[] cipherBytes = new byte[combined.length - GCM_IV_BYTES];
            System.arraycopy(combined, GCM_IV_BYTES, cipherBytes, 0, cipherBytes.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherBytes);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("凭证密文不是合法 Base64", e);
        } catch (Exception e) {
            throw new IllegalStateException("凭证解密失败（密钥不符或密文被篡改）: " + e.getMessage(), e);
        }
    }

    // 供测试构造指定密钥（不经 @Value）
    void injectKeyForTest(String base64Key) {
        this.masterKeyBase64 = base64Key;
        validate();
    }
}
