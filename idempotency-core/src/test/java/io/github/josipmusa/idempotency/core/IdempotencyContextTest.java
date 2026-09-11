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
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static IdempotencyContext.Builder builder() {
        return IdempotencyContext.builder(SCOPE, "key")
                .ttl(TTL)
                .leaseDuration(LEASE)
                .waitTimeout(WAIT);
    }

    @Test
    void When_ValidFingerprint64Chars_Expect_ConstructsSuccessfully() {
        IdempotencyContext ctx = builder().fingerprint(VALID_FINGERPRINT).build();
        assertThat(ctx.requestFingerprint()).isEqualTo(VALID_FINGERPRINT);
    }

    @Test
    void When_Sha512Fingerprint128Chars_Expect_ConstructsSuccessfully() {
        String sha512 = "a".repeat(128);
        IdempotencyContext ctx = builder().fingerprint(sha512).build();
        assertThat(ctx.requestFingerprint()).isEqualTo(sha512);
    }

    @Test
    void When_FingerprintTooShort_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> builder().fingerprint("abcdef0123456").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint must be at least 16 characters");
    }

    @Test
    void When_FingerprintNonHex_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> builder().fingerprint("g".repeat(64)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint must be a hex string");
    }

    @Test
    void When_BlankFingerprint_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> builder().fingerprint("   ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint must not be blank");
    }

    @Test
    void When_NullFingerprint_Expect_ConstructsWithoutFingerprint() {
        IdempotencyContext ctx = builder().fingerprint(null).build();

        assertThat(ctx.requestFingerprint()).isNull();
        assertThat(ctx.fingerprint()).isEmpty();
    }

    @Test
    void When_BuiltWithoutFingerprint_Expect_FingerprintIsAbsent() {
        IdempotencyContext ctx = builder().build();

        assertThat(ctx.scope()).isEqualTo(SCOPE);
        assertThat(ctx.key()).isEqualTo("key");
        assertThat(ctx.ttl()).isEqualTo(TTL);
        assertThat(ctx.leaseDuration()).isEqualTo(LEASE);
        assertThat(ctx.waitTimeout()).isEqualTo(WAIT);
        assertThat(ctx.fingerprint()).isEmpty();
    }

    @Test
    void When_NothingConfigured_Expect_DefaultsMatchIdempotencyConfigDefaults() {
        IdempotencyContext ctx = IdempotencyContext.builder(SCOPE, "key").build();

        assertThat(ctx.ttl()).isEqualTo(IdempotencyConfig.defaults().defaultTtl());
        assertThat(ctx.leaseDuration()).isEqualTo(IdempotencyConfig.defaults().defaultLeaseDuration());
        assertThat(ctx.waitTimeout()).isEqualTo(IdempotencyConfig.defaults().defaultWaitTimeout());
    }

    @Test
    void When_FingerprintPresent_Expect_FingerprintAccessorReturnsIt() {
        IdempotencyContext ctx = builder().fingerprint(VALID_FINGERPRINT).build();

        assertThat(ctx.fingerprint()).contains(VALID_FINGERPRINT);
    }

    @Test
    void When_KeyTooLong_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> IdempotencyContext.builder(SCOPE, "k".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key length must not exceed 255");
    }

    @Test
    void When_KeyExactlyAtMaxLength_Expect_ConstructsSuccessfully() {
        IdempotencyContext ctx = IdempotencyContext.builder(SCOPE, "k".repeat(IdempotencyIdentity.MAX_KEY_LENGTH))
                .build();
        assertThat(ctx.key()).hasSize(IdempotencyIdentity.MAX_KEY_LENGTH);
    }

    @Test
    void When_PositiveSubMillisecondTtl_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> builder().ttl(Duration.ofNanos(1)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl must be at least 1ms");
    }

    @Test
    void When_LeaseBelowTwoMillis_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> builder().leaseDuration(Duration.ofMillis(1)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaseDuration must be at least 2ms");
    }

    @Test
    void When_WaitZero_Expect_Accepted() {
        IdempotencyContext ctx = builder().waitTimeout(Duration.ZERO).build();

        assertThat(ctx.waitTimeout()).isZero();
    }

    @Test
    void When_WaitNegative_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> builder().waitTimeout(Duration.ofMillis(-1)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitTimeout must not be negative");
    }

    @Test
    void When_ScopeBlank_Expect_Rejected() {
        assertThatThrownBy(() -> IdempotencyContext.builder("  ", "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope must not be blank");
    }

    @Test
    void When_ScopeTooLong_Expect_Rejected() {
        assertThatThrownBy(() -> IdempotencyContext.builder("s".repeat(129), "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope length must not exceed 128");
    }

    @Test
    void When_Constructed_Expect_IdentityCombinesScopeAndKey() {
        IdempotencyContext ctx = builder().fingerprint(VALID_FINGERPRINT).build();

        assertThat(ctx.identity()).isEqualTo(new IdempotencyIdentity(SCOPE, "key"));
    }

    @Test
    void When_ConstructedFromIdentity_Expect_SameIdentityInstanceAndDelegatingAccessors() {
        IdempotencyIdentity identity = new IdempotencyIdentity(SCOPE, "key");

        IdempotencyContext ctx = IdempotencyContext.builder(identity).build();

        assertThat(ctx.identity()).isSameAs(identity);
        assertThat(ctx.scope()).isEqualTo(SCOPE);
        assertThat(ctx.key()).isEqualTo("key");
    }

    @Test
    void When_ConstructedFromStrings_Expect_EqualToContextFromIdentity() {
        IdempotencyContext fromStrings =
                builder().fingerprint(VALID_FINGERPRINT).build();
        IdempotencyContext fromIdentity = IdempotencyContext.builder(new IdempotencyIdentity(SCOPE, "key"))
                .ttl(TTL)
                .leaseDuration(LEASE)
                .waitTimeout(WAIT)
                .fingerprint(VALID_FINGERPRINT)
                .build();

        assertThat(fromStrings).isEqualTo(fromIdentity).hasSameHashCodeAs(fromIdentity);
    }

    @Test
    void When_IdentityNull_Expect_Rejected() {
        assertThatThrownBy(() -> IdempotencyContext.builder((IdempotencyIdentity) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("identity must not be null");
    }
}
