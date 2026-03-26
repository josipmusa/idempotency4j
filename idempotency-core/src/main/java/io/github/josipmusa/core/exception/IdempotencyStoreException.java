package io.github.josipmusa.core.exception;

/**
 * Thrown when a store operation fails due to invalid state or
 * infrastructure errors.
 *
 * <p>Common causes:
 * <ul>
 *   <li>Calling {@code complete} or {@code release} on a key that does
 *       not exist or is not IN_PROGRESS (programming error)</li>
 *   <li>The underlying storage (database, Redis) is unreachable</li>
 * </ul>
 */
public class IdempotencyStoreException extends IdempotencyException {

    public IdempotencyStoreException(String message) {
        super(message);
    }

    public IdempotencyStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
