package io.github.josipmusa.core;

import java.time.Duration;

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
 *   <li>{@code keyRequired} = true — whether requests without an idempotency key
 *       should be rejected (true) or passed through without idempotency (false)</li>
 * </ul>
 */
public final class IdempotencyConfig {

    private final Duration defaultTtl;
    private final Duration defaultLockTimeout;
    private final boolean keyRequired;

    private IdempotencyConfig(Builder builder) {
        this.defaultTtl = builder.defaultTtl;
        this.defaultLockTimeout = builder.defaultLockTimeout;
        this.keyRequired = builder.keyRequired;
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

    public boolean keyRequired() {
        return keyRequired;
    }

    public static final class Builder {
        private Duration defaultTtl = Duration.ofHours(24);
        private Duration defaultLockTimeout = Duration.ofSeconds(10);
        private boolean keyRequired = true;

        public Builder defaultTtl(Duration ttl) {
            this.defaultTtl = ttl;
            return this;
        }

        public Builder defaultLockTimeout(Duration timeout) {
            this.defaultLockTimeout = timeout;
            return this;
        }

        public Builder keyRequired(boolean required) {
            this.keyRequired = required;
            return this;
        }

        public IdempotencyConfig build() {
            return new IdempotencyConfig(this);
        }
    }
}
