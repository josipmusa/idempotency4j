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

import org.junit.jupiter.api.Test;

class IdempotencyIdentityTest {

    @Test
    void When_ScopeAndKeyValid_Expect_Constructs() {
        IdempotencyIdentity identity = new IdempotencyIdentity("PaymentController.create", "order-42");

        assertThat(identity.scope()).isEqualTo("PaymentController.create");
        assertThat(identity.key()).isEqualTo("order-42");
    }

    @Test
    void When_ScopeBlank_Expect_Rejected() {
        assertThatThrownBy(() -> new IdempotencyIdentity("   ", "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope must not be blank");
    }

    @Test
    void When_ScopeNull_Expect_Rejected() {
        assertThatThrownBy(() -> new IdempotencyIdentity(null, "key")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void When_KeyBlank_Expect_Rejected() {
        assertThatThrownBy(() -> new IdempotencyIdentity("scope", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key must not be blank");
    }

    @Test
    void When_KeyNull_Expect_Rejected() {
        assertThatThrownBy(() -> new IdempotencyIdentity("scope", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void When_ScopeTooLong_Expect_Rejected() {
        assertThatThrownBy(() -> new IdempotencyIdentity("s".repeat(129), "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope length must not exceed 128");
    }

    @Test
    void When_ScopeExactlyAtMaxLength_Expect_Constructs() {
        IdempotencyIdentity identity = new IdempotencyIdentity("s".repeat(IdempotencyIdentity.MAX_SCOPE_LENGTH), "key");

        assertThat(identity.scope()).hasSize(128);
    }

    @Test
    void When_KeyTooLong_Expect_Rejected() {
        assertThatThrownBy(() -> new IdempotencyIdentity("scope", "k".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key length must not exceed 255");
    }

    @Test
    void When_KeyExactlyAtMaxLength_Expect_Constructs() {
        IdempotencyIdentity identity = new IdempotencyIdentity("scope", "k".repeat(IdempotencyIdentity.MAX_KEY_LENGTH));

        assertThat(identity.key()).hasSize(255);
    }

    @Test
    void When_SameScopeAndKey_Expect_Equal() {
        assertThat(new IdempotencyIdentity("a", "k")).isEqualTo(new IdempotencyIdentity("a", "k"));
    }

    @Test
    void When_SameKeyDifferentScope_Expect_NotEqual() {
        assertThat(new IdempotencyIdentity("a", "k")).isNotEqualTo(new IdempotencyIdentity("b", "k"));
    }
}
