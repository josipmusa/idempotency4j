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

/**
 * Application-level defaults for idempotency behavior.
 *
 * <p>Used by the adapter layer to resolve {@link IdempotencyContext} when
 * per-operation values are not specified. The engine reads only
 * {@link #completionFailurePolicy()} from it; everything else it sees has already been
 * resolved into the context it is handed.
 *
 * <p>Transport-specific settings do not live here. The HTTP header carrying
 * the key, for instance, is configured on
 * {@code io.github.josipmusa.idempotency.spring.web.WebIdempotencyConfig}.
 *
 * <h2>Defaults</h2>
 * <ul>
 *   <li>{@code defaultTtl} = 24 hours — how long completed payloads are kept</li>
 *   <li>{@code defaultLeaseDuration} = 30 seconds — how long an acquisition is protected</li>
 *   <li>{@code defaultWaitTimeout} = 10 seconds — how long a second caller blocks</li>
 *   <li>{@code completionFailurePolicy} = {@link CompletionFailurePolicy#PROPAGATE} — what
 *       happens when the action ran but its completion could not be recorded</li>
 * </ul>
 */
public final class IdempotencyConfig {

    private final Duration defaultTtl;
    private final Duration defaultLeaseDuration;
    private final Duration defaultWaitTimeout;
    private final CompletionFailurePolicy completionFailurePolicy;

    private IdempotencyConfig(Builder builder) {
        this.defaultTtl = builder.defaultTtl;
        this.defaultLeaseDuration = builder.defaultLeaseDuration;
        this.defaultWaitTimeout = builder.defaultWaitTimeout;
        this.completionFailurePolicy = builder.completionFailurePolicy;
    }

    /**
     * Returns a new builder with all defaults applied.
     *
     * @return a builder for constructing {@link IdempotencyConfig}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns an {@link IdempotencyConfig} with all defaults: 24h TTL, a 30s
     * lease, and a 10s wait.
     *
     * @return a default config instance
     */
    public static IdempotencyConfig defaults() {
        return builder().build();
    }

    public Duration defaultTtl() {
        return defaultTtl;
    }

    public Duration defaultLeaseDuration() {
        return defaultLeaseDuration;
    }

    public Duration defaultWaitTimeout() {
        return defaultWaitTimeout;
    }

    /**
     * What the engine does when the action ran but its completion could not be recorded.
     *
     * @return the configured policy; never {@code null}
     */
    public CompletionFailurePolicy completionFailurePolicy() {
        return completionFailurePolicy;
    }

    @Override
    public String toString() {
        return "IdempotencyConfig{defaultTtl=" + defaultTtl + ", defaultLeaseDuration=" + defaultLeaseDuration
                + ", defaultWaitTimeout=" + defaultWaitTimeout + ", completionFailurePolicy="
                + completionFailurePolicy + "}";
    }

    public static final class Builder {

        private Duration defaultTtl = Duration.ofHours(24);
        private Duration defaultLeaseDuration = Duration.ofSeconds(30);
        private Duration defaultWaitTimeout = Duration.ofSeconds(10);
        private CompletionFailurePolicy completionFailurePolicy = CompletionFailurePolicy.PROPAGATE;

        /**
         * Sets the default TTL for completed idempotency records.
         *
         * <p>After this duration the record expires and the key can be reused
         * for a new operation. Defaults to 24 hours.
         *
         * @param ttl must be at least one millisecond
         * @return this builder
         * @throws IllegalArgumentException if {@code ttl} is less than one millisecond
         */
        public Builder defaultTtl(Duration ttl) {
            Objects.requireNonNull(ttl, "defaultTtl must not be null");
            this.defaultTtl = ttl;
            return this;
        }

        /**
         * Sets the default lease duration for an acquisition.
         *
         * <p>The lease is how long this acquisition is protected. If the holder
         * crashes without completing or releasing, the record becomes stealable
         * once the lease expires, so this value sets the crash-detection window.
         * The engine's heartbeat extends the lease at half this interval.
         *
         * <p>Defaults to 30 seconds. Minimum is 2 ms (the engine divides by 2
         * for the heartbeat interval, so values below 2 ms are rejected).
         *
         * @param leaseDuration must be at least 2 ms
         * @return this builder
         */
        public Builder defaultLeaseDuration(Duration leaseDuration) {
            Objects.requireNonNull(leaseDuration, "defaultLeaseDuration must not be null");
            this.defaultLeaseDuration = leaseDuration;
            return this;
        }

        /**
         * Sets the default wait timeout for an in-flight record.
         *
         * <p>A second caller arriving while the identity is IN_PROGRESS blocks
         * inside the store for up to this duration waiting for a result. If the
         * holder does not finish within this window, the second caller receives
         * {@link AcquireResult.InFlight}.
         *
         * <p>{@link Duration#ZERO} is valid and means "do not block": the store
         * returns {@code InFlight} on the first look. Defaults to 10 seconds.
         *
         * @param waitTimeout must not be negative
         * @return this builder
         */
        public Builder defaultWaitTimeout(Duration waitTimeout) {
            Objects.requireNonNull(waitTimeout, "defaultWaitTimeout must not be null");
            this.defaultWaitTimeout = waitTimeout;
            return this;
        }

        /**
         * Sets what the engine does when the action ran but its completion could not be
         * recorded.
         *
         * <p>Defaults to {@link CompletionFailurePolicy#PROPAGATE}. An HTTP adapter
         * normally wants {@link CompletionFailurePolicy#LOG_AND_RETURN} instead, so a
         * response the handler already produced still reaches the client.
         *
         * @param completionFailurePolicy the policy to apply
         * @return this builder
         */
        public Builder completionFailurePolicy(CompletionFailurePolicy completionFailurePolicy) {
            this.completionFailurePolicy =
                    Objects.requireNonNull(completionFailurePolicy, "completionFailurePolicy must not be null");
            return this;
        }

        /**
         * Constructs the {@link IdempotencyConfig} with the configured values.
         *
         * @return a new immutable config instance
         * @throws IllegalArgumentException if any value fails validation
         *         ({@code defaultTtl} must be positive; {@code defaultLeaseDuration}
         *         must be &ge; 2 ms; {@code defaultWaitTimeout} must not be negative)
         */
        public IdempotencyConfig build() {
            if (defaultTtl.toMillis() < 1) {
                throw new IllegalArgumentException("defaultTtl must be at least 1ms, got: " + defaultTtl);
            }
            if (defaultLeaseDuration.toMillis() < 2) {
                throw new IllegalArgumentException(
                        "defaultLeaseDuration must be at least 2ms (engine divides by 2 for heartbeat interval), got: "
                                + defaultLeaseDuration);
            }
            if (defaultWaitTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "defaultWaitTimeout must not be negative, got: " + defaultWaitTimeout);
            }
            return new IdempotencyConfig(this);
        }
    }
}
