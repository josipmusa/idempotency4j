package io.github.josipmusa.core;

import java.time.Duration;

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
