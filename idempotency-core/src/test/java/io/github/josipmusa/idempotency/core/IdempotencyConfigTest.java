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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class IdempotencyConfigTest {

    @Test
    void When_DefaultsUsed_Expect_TwentyFourHourTtlAndTenSecondLockTimeout() {
        IdempotencyConfig config = IdempotencyConfig.defaults();

        assertThat(config.defaultTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(config.defaultLockTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void When_ZeroTtl_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() ->
                        IdempotencyConfig.builder().defaultTtl(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultTtl must be at least 1ms");
    }

    @Test
    void When_NegativeTtl_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> IdempotencyConfig.builder()
                        .defaultTtl(Duration.ofSeconds(-1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultTtl must be at least 1ms");
    }

    @Test
    void When_PositiveSubMillisecondTtl_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> IdempotencyConfig.builder()
                        .defaultTtl(Duration.ofNanos(1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultTtl must be at least 1ms");
    }

    @Test
    void When_LockTimeoutBelowMinimum_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> IdempotencyConfig.builder()
                        .defaultLockTimeout(Duration.ofMillis(1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultLockTimeout must be at least 2ms");
    }
}
