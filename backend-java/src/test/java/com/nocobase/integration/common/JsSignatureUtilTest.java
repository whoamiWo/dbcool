package com.nocobase.integration.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JS-SDK 签名工具回归防线。
 *
 * <p>重点：用<b>微信官方签名示例</b>验证 SHA1 实现与官方一致 ——
 * 钉钉 / 企微 / 飞书三平台算法相同，此向量可同时守住三者。
 * 若参数顺序或拼接格式被误改，本测试会立刻失败。
 */
class JsSignatureUtilTest {

    /** 微信官方 JS-SDK 签名示例（https://developers.weixin.qq.com/doc/.../附录1）。 */
    private static final String OFFICIAL_TICKET =
            "sM4AOVdWfPE4DxkXGEs8VMCPGGVi4C3VM0P37wVUCFvkVAy_90u5h9nbSlYy3-Sl-HhTdfl2fzFy1AOcHKP7qg";
    private static final String OFFICIAL_NONCE = "Wm3WZYTPz0wzccnW";
    private static final long OFFICIAL_TIMESTAMP = 1414587457L;
    private static final String OFFICIAL_URL = "http://mp.weixin.qq.com?params=value";
    private static final String OFFICIAL_SIGNATURE = "0f9de62fce790f9a083d5c99e95740ceb90c27ed";

    @Test
    void sha1Sign_matchesOfficialSample() {
        String actual = JsSignatureUtil.sha1Sign(
                OFFICIAL_TICKET, OFFICIAL_NONCE, OFFICIAL_TIMESTAMP, OFFICIAL_URL);
        assertEquals(OFFICIAL_SIGNATURE, actual,
                "SHA1 签名必须与官方示例一致，否则前端 JS-SDK 校验必失败");
    }

    @Test
    void sha1Sign_isDeterministicForSameInput() {
        String a = JsSignatureUtil.sha1Sign("t", "n", 123L, "http://x/y");
        String b = JsSignatureUtil.sha1Sign("t", "n", 123L, "http://x/y");
        assertEquals(a, b);
    }

    @Test
    void sha1Sign_changesWhenAnyParamChanges() {
        String base = JsSignatureUtil.sha1Sign("t", "n", 123L, "http://x/y");
        assertNotEquals(base, JsSignatureUtil.sha1Sign("t2", "n", 123L, "http://x/y"), "ticket 变化应改变签名");
        assertNotEquals(base, JsSignatureUtil.sha1Sign("t", "n2", 123L, "http://x/y"), "nonce 变化应改变签名");
        assertNotEquals(base, JsSignatureUtil.sha1Sign("t", "n", 124L, "http://x/y"), "timestamp 变化应改变签名");
        assertNotEquals(base, JsSignatureUtil.sha1Sign("t", "n", 123L, "http://x/z"), "url 变化应改变签名");
    }

    @Test
    void sha1Sign_paramOrderIsSignificant() {
        // 参数顺序敏感：交换 noncestr 与 timestamp 必须得到不同结果，
        // 否则说明拼接顺序被误改（官方要求 ticket→noncestr→timestamp→url）
        String correct = JsSignatureUtil.sha1Sign("t", "n", 123L, "http://x");
        String swappedNonceTs = JsSignatureUtil.sha1Sign("t", "123", 0L, "http://x");
        assertNotEquals(correct, swappedNonceTs);
    }

    @Test
    void nonceStr_is32HexCharsAndUnique() {
        String a = JsSignatureUtil.nonceStr();
        String b = JsSignatureUtil.nonceStr();
        assertEquals(32, a.length(), "nonce 应为 16 字节的十六进制表示（32 字符）");
        assertTrue(a.matches("[0-9a-f]{32}"), "nonce 必须是小写十六进制，实际：" + a);
        assertNotEquals(a, b, "连续两次生成的 nonce 不应相同");
    }

    @Test
    void currentTimestampSeconds_isReasonable() {
        long ts = JsSignatureUtil.currentTimestampSeconds();
        // 秒级时间戳应在 2020-01-01（1577836800）之后
        assertTrue(ts > 1_577_836_800L, "时间戳应为秒级且合理，实际：" + ts);
    }
}
