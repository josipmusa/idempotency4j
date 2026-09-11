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

    private static final String SCOPE = "OrderController.create";
    private static final String VALID_FINGERPRINT = "a".repeat(64);
    private static final Duration TTL = Duration.ofHours(1);
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void When_ValidFingerprint64Chars_Expect_ConstructsSuccessfully() {
        IdempotencyContext ctx = new IdempotencyContext(SCOPE, "key", TTL, LOCK_TIMEOUT, VALID_FINGERPRINT);
        assertThat(ctx.requestFingerprint()).isEqualTo(VALID_FINGERPRINT);
    }

    @Test
    void When_Sha512Fingerprint128Chars_Expect_ConstructsSuccessfully() {
        String sha512 = "a".repeat(128);
        IdempotencyContext ctx = new IdempotencyContext(SCOPE, "key", TTL, LOCK_TIMEOUT, sha512);
        assertThat(ctx.requestFingerprint()).isEqualTo(sha512);
    }

    @Test
    void When_FingerprintTooShort_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new IdempotencyContext(SCOPE, "key", TTL, LOCK_TIMEOUT, "abcdef0123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint must be at least 16 characters");
    }

    @Test
    void When_FingerprintNonHex_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new IdempotencyContext(SCOPE, "key", TTL, LOCK_TIMEOUT, "g".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint must be a hex string");
    }

    @Test
    void When_BlankFingerprint_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new IdempotencyContext(SCOPE, "key", TTL, LOCK_TIMEOUT, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint must not be blank");
    }

    @Test
    void When_NullFingerprint_Expect_ConstructsWithoutFingerprint() {
        IdempotencyContext ctx = new IdempotencyContext(SCOPE, "key", TTL, LOCK_TIMEOUT, null);

        assertThat(ctx.requestFingerprint()).isNull();
        assertThat(ctx.fingerprint()).isEmpty();
    }

    @Test
    void When_BuiltWithoutFingerprint_Expect_FingerprintIsAbsent() {
        IdempotencyContext ctx = IdempotencyContext.withoutFingerprint(SCOPE, "key", TTL, LOCK_TIMEOUT);

        assertThat(ctx.scope()).isEqualTo(SCOPE);
        assertThat(ctx.key()).isEqualTo("key");
        assertThat(ctx.ttl()).isEqualTo(TTL);
        assertThat(ctx.lockTimeout()).isEqualTo(LOCK_TIMEOUT);
        assertThat(ctx.fingerprint()).isEmpty();
    }

    @Test
    void When_FingerprintPresent_Expect_FingerprintAccessorReturnsIt() {
        IdempotencyContext ctx = new IdempotencyContext(SCOPE, "key", TTL, LOCK_TIMEOUT, VALID_FINGERPRINT);

        assertThat(ctx.fingerprint()).contains(VALID_FINGERPRINT);
    }

    @Test
    void When_KeyTooLong_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new IdempotencyContext(SCOPE, "k".repeat(256), TTL, LOCK_TIMEOUT, VALID_FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key length must not exceed 255");
    }

    @Test
    void When_KeyExactlyAtMaxLength_Expect_ConstructsSuccessfully() {
        IdempotencyContext ctx = new IdempotencyContext(
                SCOPE, "k".repeat(IdempotencyIdentity.MAX_KEY_LENGTH), TTL, LOCK_TIMEOUT, VALID_FINGERPRINT);
        assertThat(ctx.key()).hasSize(IdempotencyIdentity.MAX_KEY_LENGTH);
    }

    @Test
    void When_PositiveSubMillisecondTtl_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() ->
                        new IdempotencyContext(SCOPE, "key", Duration.ofNanos(1), LOCK_TIMEOUT, VALID_FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl must be at least 1ms");
    }

    @Test
    void When_ScopeBlank_Expect_Rejected() {
        assertThatThrownBy(() -> new IdempotencyContext("  ", "key", TTL, LOCK_TIMEOUT, VALID_FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope must not be blank");
    }

    @Test
    void When_ScopeTooLong_Expect_Rejected() {
        assertThatThrownBy(() -> new IdempotencyContext("s".repeat(129), "key", TTL, LOCK_TIMEOUT, VALID_FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope length must not exceed 128");
    }

    @Test
    void When_Constructed_Expect_IdentityCombinesScopeAndKey() {
        IdempotencyContext ctx = new IdempotencyContext(SCOPE, "key", TTL, LOCK_TIMEOUT, VALID_FINGERPRINT);

        assertThat(ctx.identity()).isEqualTo(new IdempotencyIdentity(SCOPE, "key"));
    }

    @Test
    void When_ConstructedFromIdentity_Expect_SameIdentityInstanceAndDelegatingAccessors() {
        IdempotencyIdentity identity = new IdempotencyIdentity(SCOPE, "key");

        IdempotencyContext ctx = new IdempotencyContext(identity, TTL, LOCK_TIMEOUT, VALID_FINGERPRINT);

        assertThat(ctx.identity()).isSameAs(identity);
        assertThat(ctx.scope()).isEqualTo(SCOPE);
        assertThat(ctx.key()).isEqualTo("key");
    }

    @Test
    void When_ConstructedFromStrings_Expect_EqualToContextFromIdentity() {
        IdempotencyContext fromStrings = new IdempotencyContext(SCOPE, "key", TTL, LOCK_TIMEOUT, VALID_FINGERPRINT);
        IdempotencyContext fromIdentity =
                new IdempotencyContext(new IdempotencyIdentity(SCOPE, "key"), TTL, LOCK_TIMEOUT, VALID_FINGERPRINT);

        assertThat(fromStrings).isEqualTo(fromIdentity);
    }

    @Test
    void When_IdentityNull_Expect_Rejected() {
        assertThatThrownBy(() -> new IdempotencyContext(null, TTL, LOCK_TIMEOUT, VALID_FINGERPRINT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("identity must not be null");
    }
}
