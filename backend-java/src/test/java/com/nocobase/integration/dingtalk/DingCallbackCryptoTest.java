package com.nocobase.integration.dingtalk;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingCallbackCryptoTest {
    private static final String TOKEN = "dingTalkCallbackToken";
    private static final String AES_KEY = "1234567890123456789012345678901234567890123";
    private static final String CORP_ID = "dingTestCorpId";
    private static final String TIME_STAMP = "1700000000000";
    private static final String NONCE = "nonce123";

    /**
     * Independently calculated using SHA-1 and OpenSSL aes-256-cbc -nopad.
     * The fixed random prefix is bytes 00..0f, so this does not depend on the
     * implementation under test generating the expected value.
     */
    private static final String OFFICIAL_ALGORITHM_ENCRYPT =
            "MM4U7iBXgykjSwuA/oGS0e1vKNxBBlfHP4q0OVWtVSuG0O9A7/8msWRTP4tUcf0IZBWsykpMmW156BXDA25vEA==";
    private static final String OFFICIAL_ALGORITHM_SIGNATURE =
            "eae9b711026d3f66db0d43662de971c4eec46d3a";

    private final DingCallbackCrypto crypto = new DingCallbackCrypto(TOKEN, AES_KEY, CORP_ID);

    @Test
    void officialAlgorithmFixedVector_decryptsAndComputesSignature() {
        assertEquals(OFFICIAL_ALGORITHM_SIGNATURE, DingCallbackCrypto.computeSignature(
                TOKEN, TIME_STAMP, NONCE, OFFICIAL_ALGORITHM_ENCRYPT));
        assertEquals("success", crypto.decrypt(
                OFFICIAL_ALGORITHM_SIGNATURE, TIME_STAMP, NONCE, OFFICIAL_ALGORITHM_ENCRYPT));
    }

    @Test
    void successAck_roundTripsWithExactCheckUrlJsonFields() {
        Map<String, String> ack = crypto.encryptSuccess();

        assertEquals(4, ack.size());
        assertTrue(ack.keySet().containsAll(Arrays.asList("msg_signature", "encrypt", "timeStamp", "nonce")));
        assertEquals(DingCallbackCrypto.computeSignature(
                TOKEN, ack.get("timeStamp"), ack.get("nonce"), ack.get("encrypt")),
                ack.get("msg_signature"));
        assertEquals("success", crypto.decrypt(
                ack.get("msg_signature"), ack.get("timeStamp"), ack.get("nonce"), ack.get("encrypt")));
    }

    @Test
    void successAck_supportsRepeatedNormalCallbackRoundTrips() {
        Map<String, String> first = crypto.encryptSuccess();
        Map<String, String> second = crypto.encryptSuccess();

        assertEquals("success", crypto.decrypt(first.get("msg_signature"),
                first.get("timeStamp"), first.get("nonce"), first.get("encrypt")));
        assertEquals("success", crypto.decrypt(second.get("msg_signature"),
                second.get("timeStamp"), second.get("nonce"), second.get("encrypt")));
    }

    @Test
    void wrongSignature_isRejectedBeforeDecryption() {
        DingCallbackCrypto.DingCallbackCryptoException error = assertThrows(
                DingCallbackCrypto.DingCallbackCryptoException.class,
                () -> crypto.decrypt("0000000000000000000000000000000000000000",
                        TIME_STAMP, NONCE, OFFICIAL_ALGORITHM_ENCRYPT));
        assertTrue(error.getMessage().contains("signature"));
    }

    @Test
    void tamperedCiphertext_isRejectedEvenWhenSignatureIsRecomputed() {
        byte[] encrypted = Base64.getDecoder().decode(OFFICIAL_ALGORITHM_ENCRYPT);
        encrypted[encrypted.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(encrypted);
        String signature = DingCallbackCrypto.computeSignature(TOKEN, TIME_STAMP, NONCE, tampered);

        assertThrows(DingCallbackCrypto.DingCallbackCryptoException.class,
                () -> crypto.decrypt(signature, TIME_STAMP, NONCE, tampered));
    }

    @Test
    void wrongCorpId_isRejected() {
        String signature = DingCallbackCrypto.computeSignature(
                TOKEN, TIME_STAMP, NONCE, OFFICIAL_ALGORITHM_ENCRYPT);
        DingCallbackCrypto otherCorpCrypto = new DingCallbackCrypto(TOKEN, AES_KEY, "evilTestCorpId");

        DingCallbackCrypto.DingCallbackCryptoException error = assertThrows(
                DingCallbackCrypto.DingCallbackCryptoException.class,
                () -> otherCorpCrypto.decrypt(signature, TIME_STAMP, NONCE, OFFICIAL_ALGORITHM_ENCRYPT));
        assertTrue(error.getMessage().contains("corpId"), error.getMessage());
    }

    @Test
    void malformedUtf8_isRejectedInsteadOfUsingReplacementCharacters() {
        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};
        String encrypted = encryptPayload(invalidUtf8, CORP_ID.getBytes(StandardCharsets.UTF_8), null);
        String signature = DingCallbackCrypto.computeSignature(TOKEN, TIME_STAMP, NONCE, encrypted);

        DingCallbackCrypto.DingCallbackCryptoException error = assertThrows(
                DingCallbackCrypto.DingCallbackCryptoException.class,
                () -> crypto.decrypt(signature, TIME_STAMP, NONCE, encrypted));
        assertTrue(error.getMessage().contains("UTF-8"));
    }

    @Test
    void malformedDeclaredMessageLengths_areRejected() {
        assertMalformedLength(-1);
        assertMalformedLength(6);
        assertMalformedLength(Integer.MAX_VALUE);
    }

    @Test
    void tooShortPlaintext_isRejected() {
        byte[] shortPayload = new byte[10];
        Arrays.fill(shortPayload, (byte) 0x41);
        String encrypted = encryptRawPlaintext(shortPayload);
        String signature = DingCallbackCrypto.computeSignature(TOKEN, TIME_STAMP, NONCE, encrypted);

        DingCallbackCrypto.DingCallbackCryptoException error = assertThrows(
                DingCallbackCrypto.DingCallbackCryptoException.class,
                () -> crypto.decrypt(signature, TIME_STAMP, NONCE, encrypted));
        assertTrue(error.getMessage().contains("too short"));
    }

    @Test
    void invalidBase64AndConstructorKeys_failClearly() {
        String invalidBase64 = "not-base64!";
        String signature = DingCallbackCrypto.computeSignature(TOKEN, TIME_STAMP, NONCE, invalidBase64);
        assertThrows(DingCallbackCrypto.DingCallbackCryptoException.class,
                () -> crypto.decrypt(signature, TIME_STAMP, NONCE, invalidBase64));
        assertThrows(IllegalArgumentException.class,
                () -> new DingCallbackCrypto(TOKEN, "short", CORP_ID));
        assertThrows(IllegalArgumentException.class,
                () -> new DingCallbackCrypto(TOKEN, "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!", CORP_ID));
    }

    private void assertMalformedLength(int declaredLength) {
        String encrypted = encryptPayload("success".getBytes(StandardCharsets.UTF_8),
                CORP_ID.getBytes(StandardCharsets.UTF_8), declaredLength);
        String signature = DingCallbackCrypto.computeSignature(TOKEN, TIME_STAMP, NONCE, encrypted);

        assertThrows(DingCallbackCrypto.DingCallbackCryptoException.class,
                () -> crypto.decrypt(signature, TIME_STAMP, NONCE, encrypted));
    }

    private String encryptPayload(byte[] message, byte[] corpId, Integer declaredLength) {
        ByteBuffer plaintext = ByteBuffer.allocate(16 + 4 + message.length + corpId.length);
        for (int i = 0; i < 16; i++) {
            plaintext.put((byte) i);
        }
        plaintext.putInt(declaredLength == null ? message.length : declaredLength);
        plaintext.put(message);
        plaintext.put(corpId);
        return encryptRawPlaintext(plaintext.array());
    }

    private String encryptRawPlaintext(byte[] plaintext) {
        try {
            int padding = 32 - plaintext.length % 32;
            byte[] padded = Arrays.copyOf(plaintext, plaintext.length + padding);
            Arrays.fill(padded, plaintext.length, padded.length, (byte) padding);
            byte[] key = Base64.getDecoder().decode(AES_KEY + "=");
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(key, 0, 16));
            return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        } catch (Exception e) {
            throw new AssertionError("Test encryption failed", e);
        }
    }
}
