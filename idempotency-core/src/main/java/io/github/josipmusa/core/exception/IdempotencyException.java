package io.github.josipmusa.core.exception;

/**
 * Base exception for all idempotency-related errors.
 *
 * <p>Unchecked (extends {@link RuntimeException}) because idempotency
 * failures are infrastructure errors that callers typically cannot
 * recover from in-line — they should propagate to the error handler.
 *
 * @see IdempotencyStoreException for storage/persistence failures
 * @see IdempotencyLockTimeoutException for lock wait timeouts
 */
public class IdempotencyException extends RuntimeException {
    public IdempotencyException(String message) {
        super(message);
    }

    public IdempotencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
