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

/** Immutable operational configuration for {@link RedisIdempotencyStore}. */
public final class RedisIdempotencyStoreConfig {

    public static final String DEFAULT_KEY_PREFIX = "idempotency4j:";

    private final String keyPrefix;
    private final Duration pollInterval;
    private final Duration retentionGrace;
    private final int purgeBatchSize;
    private final int maxPurgePagesPerCall;
    private final RedisReplicaAcknowledgement replicaAcknowledgement;

    private RedisIdempotencyStoreConfig(Builder builder) {
        keyPrefix = Objects.requireNonNull(builder.keyPrefix, "keyPrefix must not be null");
        pollInterval = Objects.requireNonNull(builder.pollInterval, "pollInterval must not be null");
        retentionGrace = Objects.requireNonNull(builder.retentionGrace, "retentionGrace must not be null");
        replicaAcknowledgement =
                Objects.requireNonNull(builder.replicaAcknowledgement, "replicaAcknowledgement must not be null");
        purgeBatchSize = builder.purgeBatchSize;
        maxPurgePagesPerCall = builder.maxPurgePagesPerCall;

        if (keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        requireAtLeastOneMillisecond(pollInterval, "pollInterval");
        if (retentionGrace.isNegative()) {
            throw new IllegalArgumentException("retentionGrace must not be negative, got: " + retentionGrace);
        }
        if (!retentionGrace.isZero() && retentionGrace.toMillis() < 1) {
            throw new IllegalArgumentException("retentionGrace must be zero or at least 1ms, got: " + retentionGrace);
        }
        if (purgeBatchSize <= 0) {
            throw new IllegalArgumentException("purgeBatchSize must be positive, got: " + purgeBatchSize);
        }
        if (maxPurgePagesPerCall <= 0) {
            throw new IllegalArgumentException("maxPurgePagesPerCall must be positive, got: " + maxPurgePagesPerCall);
        }
    }

    public static RedisIdempotencyStoreConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String keyPrefix() {
        return keyPrefix;
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    public Duration retentionGrace() {
        return retentionGrace;
    }

    public int purgeBatchSize() {
        return purgeBatchSize;
    }

    public int maxPurgePagesPerCall() {
        return maxPurgePagesPerCall;
    }

    public RedisReplicaAcknowledgement replicaAcknowledgement() {
        return replicaAcknowledgement;
    }

    private static void requireAtLeastOneMillisecond(Duration value, String name) {
        if (value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least 1ms, got: " + value);
        }
    }

    public static final class Builder {

        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private Duration pollInterval = Duration.ofMillis(50);
        private Duration retentionGrace = Duration.ofHours(1);
        private int purgeBatchSize = 500;
        private int maxPurgePagesPerCall = 100;
        private RedisReplicaAcknowledgement replicaAcknowledgement = RedisReplicaAcknowledgement.disabled();

        private Builder() {}

        public Builder keyPrefix(String value) {
            keyPrefix = value;
            return this;
        }

        public Builder pollInterval(Duration value) {
            pollInterval = value;
            return this;
        }

        public Builder retentionGrace(Duration value) {
            retentionGrace = value;
            return this;
        }

        public Builder purgeBatchSize(int value) {
            purgeBatchSize = value;
            return this;
        }

        public Builder maxPurgePagesPerCall(int value) {
            maxPurgePagesPerCall = value;
            return this;
        }

        public Builder replicaAcknowledgement(RedisReplicaAcknowledgement value) {
            replicaAcknowledgement = value;
            return this;
        }

        public RedisIdempotencyStoreConfig build() {
            return new RedisIdempotencyStoreConfig(this);
        }
    }
}
