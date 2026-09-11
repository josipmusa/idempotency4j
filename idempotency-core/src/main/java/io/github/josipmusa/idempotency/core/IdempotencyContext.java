/*
 * Copyright 2026 Josip Musa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.josipmusa.idempotency.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Fully resolved parameters for a single idempotent operation.
 *
 * <p>Built by the adapter layer (e.g. Spring filter, message listener) by merging
 * {@link IdempotencyConfig} defaults with per-operation values. Passed to
 * {@link IdempotencyEngine#execute} and from there to the store.
 *
 * <p>Every field except {@code requestFingerprint} is required. The context must
 * be fully resolved before it reaches the engine.
 *
 * @param identity    what the store dedupes on: the scope naming the unit of work (a
 *                    handler method, a listener, a job) together with the idempotency
 *                    key, typically from an HTTP header (e.g. {@code Idempotency-Key})
 *                    or a message identifier. Two operations with the same identity
 *                    are duplicates; the same key under two scopes is two operations.
 *                    See {@link IdempotencyIdentity} for the limits.
 * @param ttl         how long a completed payload is kept before the key
 *                    can be reused. Determines the deduplication window.
 * @param lockTimeout how long a second caller will wait for an in-flight
 *                    operation to complete before giving up with
 *                    {@link AcquireResult.LockTimeout}. Also used as the
 *                    initial lock expiration — if the holder crashes without
 *                    completing or releasing, the lock becomes stealable
 *                    after this duration.
 * @param requestFingerprint a hash of the request payload (e.g. SHA-256 hex), or
 *                           {@code null} when the caller has no payload to
 *                           fingerprint. Used to detect when the same idempotency
 *                           key is reused with a different payload. Must be a hex
 *                           string of at least 16 characters when present; a blank
 *                           string is rejected — use {@code null} to say "none".
 */
public record IdempotencyContext(
        IdempotencyIdentity identity, Duration ttl, Duration lockTimeout, String requestFingerprint) {

    private static final Duration MIN_LOCK_TIMEOUT = Duration.ofMillis(2);
    private static final int MIN_FINGERPRINT_LENGTH = 16;
    private static final Pattern HEX_PATTERN = Pattern.compile("[0-9a-fA-F]+");

    public IdempotencyContext {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(lockTimeout, "lockTimeout must not be null");

        if (ttl.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("ttl must be at least 1ms");
        }
        if (lockTimeout.compareTo(MIN_LOCK_TIMEOUT) < 0) {
            throw new IllegalArgumentException("lockTimeout must be at least 2ms");
        }
        if (requestFingerprint != null) {
            if (requestFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "requestFingerprint must not be blank; use null to indicate no fingerprint");
            }
            if (requestFingerprint.length() < MIN_FINGERPRINT_LENGTH) {
                throw new IllegalArgumentException("requestFingerprint must be at least " + MIN_FINGERPRINT_LENGTH
                        + " characters, got: " + requestFingerprint.length());
            }
            if (!HEX_PATTERN.matcher(requestFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "requestFingerprint must be a hex string, got: " + requestFingerprint);
            }
        }
    }

    /**
     * Creates a context from a scope and key that have not yet been combined into an
     * {@link IdempotencyIdentity}. Equivalent to the canonical constructor with
     * {@code new IdempotencyIdentity(scope, key)}.
     *
     * @param scope       the unit of work, see {@link IdempotencyIdentity}
     * @param key         the idempotency key
     * @param ttl         how long a completed payload is kept
     * @param lockTimeout how long a second caller waits for an in-flight operation
     * @param requestFingerprint the request fingerprint, or {@code null} for none
     */
    public IdempotencyContext(String scope, String key, Duration ttl, Duration lockTimeout, String requestFingerprint) {
        this(new IdempotencyIdentity(scope, key), ttl, lockTimeout, requestFingerprint);
    }

    /**
     * Creates a context for a caller that has no request payload to fingerprint —
     * a message listener or an event handler, for example, where the identity alone
     * identifies the operation.
     *
     * @param scope       the unit of work, see {@link IdempotencyIdentity}
     * @param key         the idempotency key
     * @param ttl         how long a completed payload is kept
     * @param lockTimeout how long a second caller waits for an in-flight operation
     * @return a context whose {@code requestFingerprint} is {@code null}
     */
    public static IdempotencyContext withoutFingerprint(String scope, String key, Duration ttl, Duration lockTimeout) {
        return new IdempotencyContext(scope, key, ttl, lockTimeout, null);
    }

    /**
     * Returns the scope of this operation's {@link #identity()}.
     *
     * @return the scope
     */
    public String scope() {
        return identity.scope();
    }

    /**
     * Returns the idempotency key of this operation's {@link #identity()}.
     *
     * @return the key
     */
    public String key() {
        return identity.key();
    }

    /**
     * Returns the request fingerprint, if this context carries one.
     *
     * @return the fingerprint, or {@link Optional#empty()} when the caller supplied none
     */
    public Optional<String> fingerprint() {
        return Optional.ofNullable(requestFingerprint);
    }
}
