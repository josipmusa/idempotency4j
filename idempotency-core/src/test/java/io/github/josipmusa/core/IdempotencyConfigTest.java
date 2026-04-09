package io.github.josipmusa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class IdempotencyConfigTest {

    @Test
    void When_DefaultsUsed_Expect_KeyHeaderIsIdempotencyKey() {
        assertThat(IdempotencyConfig.defaults().keyHeader()).isEqualTo("Idempotency-Key");
    }

    @Test
    void When_CustomKeyHeaderSet_Expect_KeyHeaderIsCustom() {
        IdempotencyConfig config =
                IdempotencyConfig.builder().keyHeader("X-Request-Id").build();
        assertThat(config.keyHeader()).isEqualTo("X-Request-Id");
    }

    @Test
    void When_BlankKeyHeaderProvided_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> IdempotencyConfig.builder().keyHeader("  ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyHeader must not be blank");
    }

    @Test
    void When_NullKeyHeaderProvided_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> IdempotencyConfig.builder().keyHeader(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyHeader must not be blank");
    }

    @Test
    void When_ZeroTtl_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() ->
                        IdempotencyConfig.builder().defaultTtl(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultTtl must be positive");
    }

    @Test
    void When_NegativeTtl_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> IdempotencyConfig.builder()
                        .defaultTtl(Duration.ofSeconds(-1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultTtl must be positive");
    }

    @Test
    void When_LockTimeoutBelowMinimum_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> IdempotencyConfig.builder()
                        .defaultLockTimeout(Duration.ofMillis(1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultLockTimeout must be at least 2ms");
    }

    @Test
    void When_KeyHeaderWithSpaceProvided_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(
                        () -> IdempotencyConfig.builder().keyHeader("My Header").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not permitted in an HTTP header name");
    }

    @Test
    void When_KeyHeaderWithCrLfProvided_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> IdempotencyConfig.builder()
                        .keyHeader("X-Key\r\nX-Injected")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not permitted in an HTTP header name");
    }

    @Test
    void When_KeyHeaderIsValidRfc7230Token_Expect_BuildsSuccessfully() {
        IdempotencyConfig config =
                IdempotencyConfig.builder().keyHeader("X-Idempotency-Key").build();
        assertThat(config.keyHeader()).isEqualTo("X-Idempotency-Key");
    }
}
