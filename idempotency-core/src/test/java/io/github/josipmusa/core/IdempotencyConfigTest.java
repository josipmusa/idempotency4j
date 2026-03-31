package io.github.josipmusa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
