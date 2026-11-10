package com.nocobase.integration.dingtalk;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DingTalk callback message cryptography (the event-callback AES protocol).
 * This class is deliberately independent of Spring and of HTTP controllers.
 */
public final class DingCallbackCrypto {
    private static final int AES_KEY_BYTES = 32;
    private static final int BLOCK_BYTES = 16;
    private static final int PKCS7_BLOCK_BYTES = 32;
    private static final int RANDOM_BYTES = 16;
    private static final int LENGTH_BYTES = 4;
    private static final String TRANSFORMATION = "AES/CBC/NoPadding";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String token;
    private final byte[] aesKey;
    private final byte[] corpId;
    private final SecretKeySpec keySpec;
    private final IvParameterSpec iv;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param token callback token
     * @param encodingAesKey exactly 43 Base64 characters, without the trailing '='
     * @param corpId expected corporation ID (suite key where required by DingTalk)
     */
    public DingCallbackCrypto(String token, String encodingAesKey, String corpId) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("DingTalk callback token must not be empty");
        }
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException("DingTalk encodingAesKey must contain exactly 43 characters");
        }
        if (corpId == null || corpId.isEmpty()) {
            throw new IllegalArgumentException("DingTalk corpId must not be empty");
        }
        try {
            this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DingTalk encodingAesKey is not valid Base64", e);
        }
        if (aesKey.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException("DingTalk encodingAesKey must decode to 32 bytes");
        }
        this.token = token;
        this.corpId = corpId.getBytes(StandardCharsets.UTF_8);
        this.keySpec = new SecretKeySpec(aesKey, "AES");
        this.iv = new IvParameterSpec(aesKey, 0, BLOCK_BYTES);
    }

    /**
     * Verifies the callback signature before decrypting and validating its payload.
     *
     * @return the UTF-8 callback message
     * @throws DingCallbackCryptoException if authentication or decryption fails
     */
    public String decrypt(String msgSignature, String timeStamp, String nonce, String encrypt) {
        if (msgSignature == null || timeStamp == null || nonce == null || encrypt == null) {
            throw new DingCallbackCryptoException("DingTalk callback signature and encryption fields are required");
        }
        String expectedSignature = computeSignature(token, timeStamp, nonce, encrypt);
        if (msgSignature == null || !MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.US_ASCII),
                msgSignature.getBytes(StandardCharsets.US_ASCII))) {
            throw new DingCallbackCryptoException("DingTalk callback signature verification failed");
        }

        final byte[] encrypted;
        try {
            encrypted = Base64.getDecoder().decode(encrypt);
        } catch (IllegalArgumentException e) {
            throw new DingCallbackCryptoException("DingTalk callback ciphertext is not valid Base64", e);
        }
        if (encrypted.length == 0 || encrypted.length % BLOCK_BYTES != 0) {
            throw new DingCallbackCryptoException("DingTalk callback ciphertext has an invalid AES block length");
        }

        final byte[] padded;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv);
            padded = cipher.doFinal(encrypted);
        } catch (GeneralSecurityException e) {
            throw new DingCallbackCryptoException("DingTalk callback AES decryption failed", e);
        }
        byte[] plaintext = removePkcs7Padding(padded);
        return decodeAndValidate(plaintext);
    }

    /**
     * Builds the encrypted {@code success} acknowledgement required by check_url
     * and by normal event-callback responses.
     */
    public Map<String, String> encryptSuccess() {
        byte[] message = "success".getBytes(StandardCharsets.UTF_8);
        byte[] randomPrefix = new byte[RANDOM_BYTES];
        random.nextBytes(randomPrefix);

        ByteBuffer plaintext = ByteBuffer.allocate(RANDOM_BYTES + LENGTH_BYTES + message.length + corpId.length);
        plaintext.put(randomPrefix);
        plaintext.putInt(message.length);
        plaintext.put(message);
        plaintext.put(corpId);
        byte[] padded = addPkcs7Padding(plaintext.array());

        final String encrypted;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, iv);
            encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        } catch (GeneralSecurityException e) {
            throw new DingCallbackCryptoException("DingTalk success acknowledgement encryption failed", e);
        }

        String timeStamp = Long.toString(System.currentTimeMillis());
        byte[] nonceBytes = new byte[RANDOM_BYTES];
        random.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);

        Map<String, String> response = new LinkedHashMap<>();
        response.put("msg_signature", computeSignature(token, timeStamp, nonce, encrypted));
        response.put("encrypt", encrypted);
        response.put("timeStamp", timeStamp);
        response.put("nonce", nonce);
        return response;
    }

    /** Computes lowercase-hex SHA1(sort(token, timeStamp, nonce, encrypt)). */
    public static String computeSignature(String token, String timeStamp, String nonce, String encrypt) {
        if (token == null || timeStamp == null || nonce == null || encrypt == null) {
            throw new IllegalArgumentException("DingTalk signature fields must not be null");
        }
        String[] fields = {token, timeStamp, nonce, encrypt};
        Arrays.sort(fields);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(String.join("", fields).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(HEX[(value >>> 4) & 0x0f]);
                hex.append(HEX[value & 0x0f]);
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-1 is unavailable", e);
        }
    }

    private byte[] addPkcs7Padding(byte[] plaintext) {
        int padding = PKCS7_BLOCK_BYTES - plaintext.length % PKCS7_BLOCK_BYTES;
        byte[] padded = Arrays.copyOf(plaintext, plaintext.length + padding);
        Arrays.fill(padded, plaintext.length, padded.length, (byte) padding);
        return padded;
    }

    private byte[] removePkcs7Padding(byte[] padded) {
        int padding = padded[padded.length - 1] & 0xff;
        if (padding < 1 || padding > PKCS7_BLOCK_BYTES || padding > padded.length) {
            throw new DingCallbackCryptoException("DingTalk callback plaintext has invalid PKCS#7 padding");
        }
        for (int i = padded.length - padding; i < padded.length; i++) {
            if ((padded[i] & 0xff) != padding) {
                throw new DingCallbackCryptoException("DingTalk callback plaintext has invalid PKCS#7 padding");
            }
        }
        return Arrays.copyOf(padded, padded.length - padding);
    }

    private String decodeAndValidate(byte[] plaintext) {
        int framingBytes = RANDOM_BYTES + LENGTH_BYTES;
        if (plaintext.length < framingBytes + corpId.length) {
            throw new DingCallbackCryptoException("DingTalk callback plaintext is too short");
        }
        int messageLength = ByteBuffer.wrap(plaintext, RANDOM_BYTES, LENGTH_BYTES).getInt();
        int actualMessageLength = plaintext.length - framingBytes - corpId.length;
        if (messageLength < 0 || messageLength > actualMessageLength) {
            throw new DingCallbackCryptoException("DingTalk callback plaintext has an invalid message length");
        }
        byte[] actualCorpId = Arrays.copyOfRange(plaintext, framingBytes + messageLength, plaintext.length);
        if (!MessageDigest.isEqual(corpId, actualCorpId)) {
            throw new DingCallbackCryptoException("DingTalk callback corpId does not match");
        }
        if (messageLength != actualMessageLength) {
            throw new DingCallbackCryptoException("DingTalk callback plaintext has an invalid message length");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext, framingBytes, messageLength))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new DingCallbackCryptoException("DingTalk callback message is not valid UTF-8", e);
        }
    }

    /** A checked, explicit authentication/decryption failure safe for callback handling. */
    public static final class DingCallbackCryptoException extends SecurityException {
        private static final long serialVersionUID = 1L;

        public DingCallbackCryptoException(String message) {
            super(message);
        }

        public DingCallbackCryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
