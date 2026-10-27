package com.nocobase.integration.dingtalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B4: 钉钉签名工具单测。 */
class DingTalkSignatureUtilTest {

    @Test
    void verify_validSignature_passes() {
        String secret = "test-secret";
        String ts = String.valueOf(System.currentTimeMillis());
        String sig = DingTalkSignatureUtil.sign(secret, ts);
        assertTrue(DingTalkSignatureUtil.verify(secret, ts, sig));
    }

    @Test
    void verify_wrongSecret_fails() {
        String ts = String.valueOf(System.currentTimeMillis());
        String sig = DingTalkSignatureUtil.sign("secret-A", ts);
        assertFalse(DingTalkSignatureUtil.verify("secret-B", ts, sig));
    }

    @Test
    void verify_tamperedSignature_fails() {
        String ts = String.valueOf(System.currentTimeMillis());
        String sig = DingTalkSignatureUtil.sign("secret", ts);
        assertFalse(DingTalkSignatureUtil.verify("secret", ts, sig + "x"));
    }

    @Test
    void verify_expiredTimestamp_fails() {
        String ts = String.valueOf(System.currentTimeMillis() - 400_000L);
        String sig = DingTalkSignatureUtil.sign("secret", ts);
        assertFalse(DingTalkSignatureUtil.verify("secret", ts, sig));
    }

    @Test
    void verify_nullArguments_fails() {
        assertFalse(DingTalkSignatureUtil.verify(null, "1", "sig"));
        assertFalse(DingTalkSignatureUtil.verify("secret", null, "sig"));
        assertFalse(DingTalkSignatureUtil.verify("secret", "1", null));
    }

    @Test
    void sign_nullReturnsNull() {
        assertFalse(DingTalkSignatureUtil.sign(null, "1") != null);
        assertFalse(DingTalkSignatureUtil.sign("s", null) != null);
    }
}
