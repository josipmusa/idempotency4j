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

class IdempotencyContextTest {

    private static final String VALID_FINGERPRINT = "a".repeat(64);
    private static final Duration TTL = Duration.ofHours(1);
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void When_ValidFingerprint64Chars_Expect_ConstructsSuccessfully() {
        IdempotencyContext ctx = new IdempotencyContext("key", TTL, LOCK_TIMEOUT, VALID_FINGERPRINT);
        assertThat(ctx.requestFingerprint()).isEqualTo(VALID_FINGERPRINT);
    }

    @Test
    void When_Sha512Fingerprint128Chars_Expect_ConstructsSuccessfully() {
        String sha512 = "a".repeat(128);
        IdempotencyContext ctx = new IdempotencyContext("key", TTL, LOCK_TIMEOUT, sha512);
        assertThat(ctx.requestFingerprint()).isEqualTo(sha512);
    }

    @Test
    void When_FingerprintTooShort_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new IdempotencyContext("key", TTL, LOCK_TIMEOUT, "abcdef0123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint must be at least 16 characters");
    }

    @Test
    void When_FingerprintNonHex_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new IdempotencyContext("key", TTL, LOCK_TIMEOUT, "g".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint must be a hex string");
    }

    @Test
    void When_BlankFingerprint_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new IdempotencyContext("key", TTL, LOCK_TIMEOUT, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint must not be blank");
    }

    @Test
    void When_KeyTooLong_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new IdempotencyContext("k".repeat(256), TTL, LOCK_TIMEOUT, VALID_FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key length must not exceed 255");
    }

    @Test
    void When_KeyExactlyAtMaxLength_Expect_ConstructsSuccessfully() {
        IdempotencyContext ctx = new IdempotencyContext(
                "k".repeat(IdempotencyContext.MAX_KEY_LENGTH), TTL, LOCK_TIMEOUT, VALID_FINGERPRINT);
        assertThat(ctx.key()).hasSize(IdempotencyContext.MAX_KEY_LENGTH);
    }
}
