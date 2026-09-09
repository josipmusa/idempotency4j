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
package io.github.josipmusa.idempotency.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebIdempotencyConfigTest {

    @Test
    void When_DefaultsUsed_Expect_KeyHeaderIsIdempotencyKey() {
        assertThat(WebIdempotencyConfig.defaults().keyHeader()).isEqualTo("Idempotency-Key");
    }

    @Test
    void When_CustomKeyHeaderSet_Expect_KeyHeaderIsCustom() {
        assertThat(WebIdempotencyConfig.withKeyHeader("X-Request-Id").keyHeader())
                .isEqualTo("X-Request-Id");
    }

    @Test
    void When_BlankKeyHeaderProvided_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> WebIdempotencyConfig.withKeyHeader("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyHeader must not be blank");
    }

    @Test
    void When_NullKeyHeaderProvided_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> WebIdempotencyConfig.withKeyHeader(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyHeader must not be blank");
    }

    @Test
    void When_KeyHeaderWithSpaceProvided_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> WebIdempotencyConfig.withKeyHeader("My Header"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not permitted in an HTTP header name");
    }

    @Test
    void When_KeyHeaderWithCrLfProvided_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> WebIdempotencyConfig.withKeyHeader("X-Key\r\nX-Injected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not permitted in an HTTP header name");
    }

    @Test
    void When_KeyHeaderIsValidRfc7230Token_Expect_BuildsSuccessfully() {
        assertThat(WebIdempotencyConfig.withKeyHeader("X-Idempotency-Key").keyHeader())
                .isEqualTo("X-Idempotency-Key");
    }
}
