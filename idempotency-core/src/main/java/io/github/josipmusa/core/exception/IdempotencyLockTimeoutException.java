package io.github.josipmusa.core.exception;

import java.io.Serial;
import java.time.Duration;

/**
 * Thrown by the engine when a second caller's lock timeout expires while
 * waiting for an in-flight request to complete.
 *
 * <p>This means another request holds the key as IN_PROGRESS and did not
 * complete within this caller's {@code lockTimeout}. The adapter should
 * typically return HTTP 409 Conflict or 503 Service Unavailable.
 *
 * <p>Carries the key and timeout for logging and error responses.
 */
public class IdempotencyLockTimeoutException extends IdempotencyException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String key;
    private final Duration lockTimeout;

    public IdempotencyLockTimeoutException(String key, Duration lockTimeout) {
        super("Lock timeout expired for key " + key + " with timeout " + lockTimeout);
        this.key = key;
        this.lockTimeout = lockTimeout;
    }

    public String getKey() {
        return key;
    }

    public Duration getLockTimeout() {
        return lockTimeout;
    }
}
