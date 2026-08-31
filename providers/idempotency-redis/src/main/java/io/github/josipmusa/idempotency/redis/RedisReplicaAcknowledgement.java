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
package io.github.josipmusa.idempotency.redis;

import java.time.Duration;
import java.util.Objects;

/** Optional Redis {@code WAIT} policy applied after successful mutations. */
public record RedisReplicaAcknowledgement(int requiredReplicas, Duration timeout) {

    public RedisReplicaAcknowledgement {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (requiredReplicas < 0) {
            throw new IllegalArgumentException("requiredReplicas must not be negative, got: " + requiredReplicas);
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative, got: " + timeout);
        }
        if (requiredReplicas > 0 && timeout.toMillis() < 1) {
            throw new IllegalArgumentException(
                    "timeout must be at least 1ms when replica acknowledgements are required, got: " + timeout);
        }
    }

    /** Disables replica acknowledgement, which is the default. */
    public static RedisReplicaAcknowledgement disabled() {
        return new RedisReplicaAcknowledgement(0, Duration.ZERO);
    }

    /** Requires the given number of replicas to acknowledge each mutation. */
    public static RedisReplicaAcknowledgement require(int replicas, Duration timeout) {
        return new RedisReplicaAcknowledgement(replicas, timeout);
    }

    public boolean enabled() {
        return requiredReplicas > 0;
    }
}
