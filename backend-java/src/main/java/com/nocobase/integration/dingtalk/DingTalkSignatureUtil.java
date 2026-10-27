package com.nocobase.integration.dingtalk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 钉钉回调签名工具 — 从 {@code DingTalkController.verifySignature} 提取共用。
 *
 * <p><b>算法</b>: {@code sign = Base64(HmacSHA256(appSecret, timestamp))},
 * 与钉钉开放平台回调加签规则一致。
 *
 * <p><b>安全</b>:签名比较使用 {@link MessageDigest#isEqual} 常量时间实现,
 * 防止时序侧信道攻击;时间戳校验 5 分钟窗口,防重放。
 *
 * <p>本类为纯静态工具,无状态、无依赖,便于 Controller 与 Service 共用。
 */
public final class DingTalkSignatureUtil {

    /** 时间戳允许的偏移窗口(毫秒),超出视为过期。 */
    private static final long TIMESTAMP_WINDOW_MS = 300_000L;

    private DingTalkSignatureUtil() {
        // 工具类禁止实例化
    }

    /**
     * 校验钉钉回调签名。
     *
     * @param appSecret 应用密钥(为空直接判定失败)
     * @param timestamp 回调头中的时间戳(毫秒字符串)
     * @param signature 回调头中的签名(Base64)
     * @return 校验通过返回 true
     */
    public static boolean verify(String appSecret, String timestamp, String signature) {
        if (appSecret == null || appSecret.isBlank()
                || timestamp == null || timestamp.isBlank()
                || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp);
            long now = System.currentTimeMillis();
            if (Math.abs(now - ts) > TIMESTAMP_WINDOW_MS) {
                return false;
            }

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(timestamp.getBytes(StandardCharsets.UTF_8));
            String expectedSig = Base64.getEncoder().encodeToString(digest);

            // 常量时间比较防时序攻击
            return MessageDigest.isEqual(
                    signature.getBytes(StandardCharsets.UTF_8),
                    expectedSig.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 计算签名(供测试与出站调用自检使用)。
     *
     * @return Base64 签名;参数非法返回 null
     */
    public static String sign(String appSecret, String timestamp) {
        if (appSecret == null || timestamp == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(timestamp.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return null;
        }
    }
}
