package com.nocobase.integration.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 三平台共用的 JS-SDK 签名工具（钉钉 / 企微 / 飞书算法一致）。
 */
public final class JsSignatureUtil {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsSignatureUtil() {}

    /** 生成 16 字节随机 nonce（32 位十六进制）。 */
    public static String nonceStr() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) {
            sb.append(HEX[(x >> 4) & 0xF]).append(HEX[x & 0xF]);
        }
        return sb.toString();
    }

    /**
     * 计算签名：{@code SHA1("jsapi_ticket=T&noncestr=N&timestamp=TS&url=U")}。
     *
     * <p>参数顺序必须严格按官方规范（ticket → noncestr → timestamp → url），顺序错误会导致前端校验失败。
     */
    public static String sha1Sign(String ticket, String nonceStr, long timestamp, String url) {
        String plain = "jsapi_ticket=" + ticket
                + "&noncestr=" + nonceStr
                + "&timestamp=" + timestamp
                + "&url=" + url;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte x : digest) {
                sb.append(HEX[(x >> 4) & 0xF]).append(HEX[x & 0xF]);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }

    /** 当前秒级时间戳。 */
    public static long currentTimestampSeconds() {
        return System.currentTimeMillis() / 1000;
    }
}
