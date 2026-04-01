package io.github.josipmusa.idempotency.spring.web;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a SHA-256 hex digest of a request body. The resulting 64-character
 * lowercase hex string is stored alongside the idempotency key so that reuse
 * of the same key with a different payload can be detected.
 */
public final class RequestFingerprint {

    private static final String ALGORITHM = "SHA-256";

    private RequestFingerprint() {}

    /**
     * Compute SHA-256 hex of the given body bytes.
     *
     * @param body the raw request body; must not be null
     * @return 64-character lowercase hex SHA-256 digest
     */
    public static String of(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hash = digest.digest(body);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
