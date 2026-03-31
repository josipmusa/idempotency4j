package io.github.josipmusa.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Application-level defaults for idempotency behavior.
 *
 * <p>Used by the adapter layer to resolve {@link IdempotencyContext} when
 * per-request values are not specified. The engine itself never reads this
 * class — it only sees the fully resolved context.
 *
 * <h2>Defaults</h2>
 * <ul>
 *   <li>{@code defaultTtl} = 24 hours — how long completed responses are kept</li>
 *   <li>{@code defaultLockTimeout} = 10 seconds — how long a second caller waits</li>
 * </ul>
 */
public final class IdempotencyConfig {

    private final Duration defaultTtl;
    private final Duration defaultLockTimeout;
    private final String keyHeader;

    private IdempotencyConfig(Builder builder) {
        this.defaultTtl = builder.defaultTtl;
        this.defaultLockTimeout = builder.defaultLockTimeout;
        this.keyHeader = builder.keyHeader;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static IdempotencyConfig defaults() {
        return builder().build();
    }

    public Duration defaultTtl() {
        return defaultTtl;
    }

    public Duration defaultLockTimeout() {
        return defaultLockTimeout;
    }

    public String keyHeader() {
        return keyHeader;
    }

    public static final class Builder {
        private Duration defaultTtl = Duration.ofHours(24);
        private Duration defaultLockTimeout = Duration.ofSeconds(10);
        private String keyHeader = "Idempotency-Key";

        public Builder defaultTtl(Duration ttl) {
            Objects.requireNonNull(ttl, "defaultTtl must not be null");
            this.defaultTtl = ttl;
            return this;
        }

        public Builder defaultLockTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "defaultLockTimeout must not be null");
            this.defaultLockTimeout = timeout;
            return this;
        }

        public Builder keyHeader(String keyHeader) {
            this.keyHeader = keyHeader;
            return this;
        }

        public IdempotencyConfig build() {
            if (defaultTtl.isZero() || defaultTtl.isNegative()) {
                throw new IllegalArgumentException("defaultTtl must be positive, got: " + defaultTtl);
            }
            if (defaultLockTimeout.toMillis() < 2) {
                throw new IllegalArgumentException(
                        "defaultLockTimeout must be at least 2ms (engine divides by 2 for heartbeat interval), got: "
                                + defaultLockTimeout);
            }
            if (keyHeader == null || keyHeader.isBlank()) {
                throw new IllegalArgumentException("keyHeader must not be blank");
            }
            return new IdempotencyConfig(this);
        }
    }
}
