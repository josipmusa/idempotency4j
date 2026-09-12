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
 * <p>Instances are created through {@link #builder(String, String)} or
 * {@link #builder(IdempotencyIdentity)}. Only the identity is mandatory; every other
 * value has a default, and {@code fingerprint} may be left unset.
 *
 * <pre>{@code
 * IdempotencyContext context = IdempotencyContext.builder("PaymentController.create", "key-42")
 *         .ttl(Duration.ofHours(24))
 *         .leaseDuration(Duration.ofSeconds(30))
 *         .waitTimeout(Duration.ZERO)
 *         .fingerprint(sha256Hex)
 *         .build();
 * }</pre>
 *
 * <h2>The two durations</h2>
 * <p>{@code leaseDuration} and {@code waitTimeout} answer different questions and are set
 * independently:
 * <ul>
 *   <li>{@code leaseDuration} — how long <em>this</em> acquisition is protected. If the
 *       holder crashes without completing or releasing, the record becomes stealable once
 *       the lease expires. The engine's heartbeat extends it at half the lease.</li>
 *   <li>{@code waitTimeout} — how long {@link IdempotencyStore#tryAcquire} blocks waiting
 *       for <em>someone else's</em> in-flight operation before giving up with
 *       {@link AcquireResult.InFlight}. {@link Duration#ZERO} is valid and means "do not
 *       block", which is what a message listener wants so it declines instead of parking
 *       a consumer thread.</li>
 * </ul>
 */
public final class IdempotencyContext {

    private static final Duration MIN_LEASE_DURATION = Duration.ofMillis(2);
    private static final int MIN_FINGERPRINT_LENGTH = 16;
    private static final Pattern HEX_PATTERN = Pattern.compile("[0-9a-fA-F]+");

    private final IdempotencyIdentity identity;
    private final Duration ttl;
    private final Duration leaseDuration;
    private final Duration waitTimeout;
    private final String requestFingerprint;
    private final CompletionMode completionMode;

    private IdempotencyContext(Builder builder) {
        this.identity = builder.identity;
        this.ttl = builder.ttl;
        this.leaseDuration = builder.leaseDuration;
        this.waitTimeout = builder.waitTimeout;
        this.requestFingerprint = builder.requestFingerprint;
        this.completionMode = builder.completionMode;
    }

    /**
     * Starts a builder for the given identity.
     *
     * @param identity what the store dedupes on, see {@link IdempotencyIdentity}
     * @return a builder carrying the defaults of {@link IdempotencyConfig#defaults()}
     */
    public static Builder builder(IdempotencyIdentity identity) {
        return new Builder(identity);
    }

    /**
     * Starts a builder for a scope and key that have not yet been combined into an
     * {@link IdempotencyIdentity}.
     *
     * @param scope the unit of work, see {@link IdempotencyIdentity}
     * @param key   the idempotency key
     * @return a builder carrying the defaults of {@link IdempotencyConfig#defaults()}
     */
    public static Builder builder(String scope, String key) {
        return new Builder(new IdempotencyIdentity(scope, key));
    }

    /**
     * Returns what the store dedupes on: the scope naming the unit of work (a handler
     * method, a listener, a job) together with the idempotency key, typically from an
     * HTTP header (e.g. {@code Idempotency-Key}) or a message identifier. Two operations
     * with the same identity are duplicates; the same key under two scopes is two
     * operations.
     *
     * @return the identity
     */
    public IdempotencyIdentity identity() {
        return identity;
    }

    /**
     * Returns how long a completed payload is kept before the key can be reused. This
     * determines the deduplication window.
     *
     * @return the time-to-live of a completed record
     */
    public Duration ttl() {
        return ttl;
    }

    /**
     * Returns how long this acquisition is protected before another caller may steal it.
     *
     * @return the lease duration
     */
    public Duration leaseDuration() {
        return leaseDuration;
    }

    /**
     * Returns how long {@link IdempotencyStore#tryAcquire} blocks for an in-flight record
     * before returning {@link AcquireResult.InFlight}. {@link Duration#ZERO} means the
     * store returns immediately.
     *
     * @return the wait timeout
     */
    public Duration waitTimeout() {
        return waitTimeout;
    }

    /**
     * Returns the raw request fingerprint, or {@code null} when the caller supplied none.
     *
     * @return the fingerprint, or {@code null}
     */
    public String requestFingerprint() {
        return requestFingerprint;
    }

    /**
     * Returns how the completion is recorded relative to the caller's transaction.
     *
     * @return the completion mode; never {@code null}, {@link CompletionMode#AUTONOMOUS}
     *         unless the caller asked for something else
     */
    public CompletionMode completionMode() {
        return completionMode;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IdempotencyContext other)) {
            return false;
        }
        return identity.equals(other.identity)
                && ttl.equals(other.ttl)
                && leaseDuration.equals(other.leaseDuration)
                && waitTimeout.equals(other.waitTimeout)
                && Objects.equals(requestFingerprint, other.requestFingerprint)
                && completionMode == other.completionMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, ttl, leaseDuration, waitTimeout, requestFingerprint, completionMode);
    }

    @Override
    public String toString() {
        return "IdempotencyContext{identity=" + identity + ", ttl=" + ttl + ", leaseDuration=" + leaseDuration
                + ", waitTimeout=" + waitTimeout + ", requestFingerprint=" + requestFingerprint + ", completionMode="
                + completionMode + "}";
    }

    /** Builds {@link IdempotencyContext} instances. */
    public static final class Builder {

        private final IdempotencyIdentity identity;
        private Duration ttl = Duration.ofHours(24);
        private Duration leaseDuration = Duration.ofSeconds(30);
        private Duration waitTimeout = Duration.ofSeconds(10);
        private String requestFingerprint;
        private CompletionMode completionMode = CompletionMode.AUTONOMOUS;

        private Builder(IdempotencyIdentity identity) {
            this.identity = Objects.requireNonNull(identity, "identity must not be null");
        }

        /**
         * Sets how long the completed payload is kept before the key can be reused.
         *
         * @param ttl must be at least 1 ms
         * @return this builder
         */
        public Builder ttl(Duration ttl) {
            this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
            return this;
        }

        /**
         * Sets how long this acquisition is protected before it can be stolen.
         *
         * @param leaseDuration must be at least 2 ms — the heartbeat fires at half of it
         * @return this builder
         */
        public Builder leaseDuration(Duration leaseDuration) {
            this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
            return this;
        }

        /**
         * Sets how long {@code tryAcquire} blocks for someone else's in-flight record.
         *
         * @param waitTimeout must not be negative; {@link Duration#ZERO} means do not block
         * @return this builder
         */
        public Builder waitTimeout(Duration waitTimeout) {
            this.waitTimeout = Objects.requireNonNull(waitTimeout, "waitTimeout must not be null");
            return this;
        }

        /**
         * Sets the request fingerprint used to detect a key reused with a different payload.
         *
         * @param fingerprint a hex string of at least 16 characters, or {@code null} for none.
         *                    A blank string is rejected — use {@code null} to say "none".
         * @return this builder
         */
        public Builder fingerprint(String fingerprint) {
            this.requestFingerprint = fingerprint;
            return this;
        }

        /**
         * Sets how the completion is recorded relative to the caller's transaction.
         *
         * @param completionMode {@link CompletionMode#JOIN_TRANSACTION} to commit the record
         *                       together with the action's own writes; defaults to
         *                       {@link CompletionMode#AUTONOMOUS}
         * @return this builder
         */
        public Builder completionMode(CompletionMode completionMode) {
            this.completionMode = Objects.requireNonNull(completionMode, "completionMode must not be null");
            return this;
        }

        /**
         * Constructs the context.
         *
         * @return a new immutable context
         * @throws IllegalArgumentException if any value fails validation
         */
        public IdempotencyContext build() {
            if (ttl.compareTo(Duration.ofMillis(1)) < 0) {
                throw new IllegalArgumentException("ttl must be at least 1ms");
            }
            if (leaseDuration.compareTo(MIN_LEASE_DURATION) < 0) {
                throw new IllegalArgumentException("leaseDuration must be at least 2ms");
            }
            if (waitTimeout.isNegative()) {
                throw new IllegalArgumentException("waitTimeout must not be negative");
            }
            if (requestFingerprint != null) {
                validateFingerprint(requestFingerprint);
            }
            return new IdempotencyContext(this);
        }

        private static void validateFingerprint(String fingerprint) {
            if (fingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "requestFingerprint must not be blank; use null to indicate no fingerprint");
            }
            if (fingerprint.length() < MIN_FINGERPRINT_LENGTH) {
                throw new IllegalArgumentException("requestFingerprint must be at least " + MIN_FINGERPRINT_LENGTH
                        + " characters, got: " + fingerprint.length());
            }
            if (!HEX_PATTERN.matcher(fingerprint).matches()) {
                throw new IllegalArgumentException("requestFingerprint must be a hex string, got: " + fingerprint);
            }
        }
    }
}
