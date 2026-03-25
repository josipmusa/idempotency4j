package io.github.josipmusa.core.exception;

import java.time.Duration;

public class IdempotencyLockTimeoutException extends IdempotencyException {

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
