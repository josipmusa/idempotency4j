package io.github.josipmusa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdempotencyConfigTest {

    @Test
    void defaults_keyHeaderIsIdempotencyKey() {
        assertThat(IdempotencyConfig.defaults().keyHeader()).isEqualTo("Idempotency-Key");
    }

    @Test
    void builder_customKeyHeader() {
        IdempotencyConfig config =
                IdempotencyConfig.builder().keyHeader("X-Request-Id").build();
        assertThat(config.keyHeader()).isEqualTo("X-Request-Id");
    }

    @Test
    void builder_blankKeyHeader_throws() {
        assertThatThrownBy(() -> IdempotencyConfig.builder().keyHeader("  ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyHeader must not be blank");
    }
}
