package io.github.josipmusa.core.exception;

import java.io.Serial;

/**
 * Thrown by the engine when a duplicate request is detected but the request
 * body fingerprint does not match the one stored with the original request.
 *
 * <p>This indicates the client reused an idempotency key with a different
 * payload. The adapter should return HTTP 422 Unprocessable Entity.
 */
public class IdempotencyFingerprintMismatchException extends IdempotencyException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String key;
    private final String storedFingerprint;
    private final String receivedFingerprint;

    public IdempotencyFingerprintMismatchException(String key, String storedFingerprint, String receivedFingerprint) {
        super("Request fingerprint mismatch for key '" + key + "': stored=" + storedFingerprint + ", received="
                + receivedFingerprint);
        this.key = key;
        this.storedFingerprint = storedFingerprint;
        this.receivedFingerprint = receivedFingerprint;
    }

    public String getKey() {
        return key;
    }

    public String getStoredFingerprint() {
        return storedFingerprint;
    }

    public String getReceivedFingerprint() {
        return receivedFingerprint;
    }
}
