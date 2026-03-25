package io.github.josipmusa.core;

import java.time.Duration;
import java.util.Objects;

public record IdempotencyContext(
        String key,
        Duration ttl,
        Duration lockTimeout
) {

    public IdempotencyContext {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(lockTimeout, "lockTimeout must not be null");
    }
}
