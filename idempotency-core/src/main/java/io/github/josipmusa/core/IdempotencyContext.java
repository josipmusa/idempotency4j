package io.github.josipmusa.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Fully resolved parameters for a single idempotent operation.
 *
 * <p>Built by the adapter layer (e.g. Spring interceptor) by merging
 * {@link IdempotencyConfig} defaults with per-request values from the
 * annotation and HTTP headers. Passed to {@link IdempotencyEngine#execute}
 * and from there to the store.
 *
 * <p>All fields are required — no nulls, no optionals. The context must
 * be fully resolved before it reaches the engine.
 *
 * @param key         the idempotency key, typically from an HTTP header
 *                    (e.g. {@code Idempotency-Key}). Two requests with the
 *                    same key are considered duplicates.
 * @param ttl         how long a completed response is kept before the key
 *                    can be reused. Determines the deduplication window.
 * @param lockTimeout how long a second caller will wait for an in-flight
 *                    request to complete before giving up with
 *                    {@link AcquireResult.LockTimeout}. Also used as the
 *                    initial lock expiration — if the holder crashes without
 *                    completing or releasing, the lock becomes stealable
 *                    after this duration.
 */
public record IdempotencyContext(String key, Duration ttl, Duration lockTimeout) {

    private static final Duration MIN_LOCK_TIMEOUT = Duration.ofMillis(2);
    private static final int MAX_KEY_LENGTH = 255;

    public IdempotencyContext {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(lockTimeout, "lockTimeout must not be null");

        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("key length must not exceed " + MAX_KEY_LENGTH + " characters");
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (lockTimeout.compareTo(MIN_LOCK_TIMEOUT) < 0) {
            throw new IllegalArgumentException("lockTimeout must be at least 2ms");
        }
    }
}
