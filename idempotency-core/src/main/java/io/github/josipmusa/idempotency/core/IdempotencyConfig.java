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
 * per-operation values are not specified. The engine itself never reads this
 * class — it only sees the fully resolved context.
 *
 * <p>Transport-specific settings do not live here. The HTTP header carrying
 * the key, for instance, is configured on
 * {@code io.github.josipmusa.idempotency.spring.web.WebIdempotencyConfig}.
 *
 * <h2>Defaults</h2>
 * <ul>
 *   <li>{@code defaultTtl} = 24 hours — how long completed payloads are kept</li>
 *   <li>{@code defaultLockTimeout} = 10 seconds — how long a second caller waits</li>
 * </ul>
 */
public final class IdempotencyConfig {

    private final Duration defaultTtl;
    private final Duration defaultLockTimeout;

    private IdempotencyConfig(Builder builder) {
        this.defaultTtl = builder.defaultTtl;
        this.defaultLockTimeout = builder.defaultLockTimeout;
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
     * Returns an {@link IdempotencyConfig} with all defaults: 24h TTL and a
     * 10s lock timeout.
     *
     * @return a default config instance
     */
    public static IdempotencyConfig defaults() {
        return builder().build();
    }

    public Duration defaultTtl() {
        return defaultTtl;
    }

    public Duration defaultLockTimeout() {
        return defaultLockTimeout;
    }

    @Override
    public String toString() {
        return "IdempotencyConfig{defaultTtl=" + defaultTtl + ", defaultLockTimeout=" + defaultLockTimeout + "}";
    }

    public static final class Builder {

        private Duration defaultTtl = Duration.ofHours(24);
        private Duration defaultLockTimeout = Duration.ofSeconds(10);

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
         * Sets the default lock timeout for in-flight operations.
         *
         * <p>A second caller arriving while the key is IN_PROGRESS will block
         * for up to this duration waiting for a result. If the holder does not
         * complete within this window, the second caller receives
         * {@link AcquireResult.LockTimeout}.
         *
         * <p>This value is also the initial lock expiry for the holder — if the
         * holder crashes without completing or releasing, the lock becomes
         * stealable after this duration. The heartbeat extends the lock at
         * half this interval, so this value effectively sets the crash-detection
         * window as well.
         *
         * <p>Defaults to 10 seconds. Minimum is 2 ms (the engine divides by 2
         * for the heartbeat interval, so values below 2 ms are rejected).
         *
         * @param timeout must be at least 2 ms
         * @return this builder
         * @throws IllegalArgumentException if {@code timeout} is less than 2 ms
         */
        public Builder defaultLockTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "defaultLockTimeout must not be null");
            this.defaultLockTimeout = timeout;
            return this;
        }

        /**
         * Constructs the {@link IdempotencyConfig} with the configured values.
         *
         * @return a new immutable config instance
         * @throws IllegalArgumentException if any value fails validation
         *         ({@code defaultTtl} must be positive; {@code defaultLockTimeout}
         *         must be &ge; 2 ms)
         */
        public IdempotencyConfig build() {
            if (defaultTtl.toMillis() < 1) {
                throw new IllegalArgumentException("defaultTtl must be at least 1ms, got: " + defaultTtl);
            }
            if (defaultLockTimeout.toMillis() < 2) {
                throw new IllegalArgumentException(
                        "defaultLockTimeout must be at least 2ms (engine divides by 2 for heartbeat interval), got: "
                                + defaultLockTimeout);
            }
            return new IdempotencyConfig(this);
        }
    }
}
