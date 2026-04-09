package io.github.josipmusa.core;

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
    void When_FingerprintTooShort_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new IdempotencyContext("key", TTL, LOCK_TIMEOUT, "tooshort"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint must be 64 characters");
    }

    @Test
    void When_FingerprintTooLong_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new IdempotencyContext("key", TTL, LOCK_TIMEOUT, "a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint must be 64 characters");
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
