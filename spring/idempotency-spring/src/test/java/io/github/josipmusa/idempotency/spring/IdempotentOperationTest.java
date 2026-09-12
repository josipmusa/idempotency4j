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
package io.github.josipmusa.idempotency.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.josipmusa.idempotency.core.CompletionMode;
import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class IdempotentOperationTest {

    private static final IdempotencyConfig JOINED_BY_DEFAULT = IdempotencyConfig.builder()
            .defaultCompletionMode(CompletionMode.JOIN_TRANSACTION)
            .build();

    @Test
    void When_CompletionNotSet_Expect_ConfigDefaultUsed() {
        IdempotentOperation operation = resolve("unset", JOINED_BY_DEFAULT);

        assertThat(operation.completionMode()).isEqualTo(CompletionMode.JOIN_TRANSACTION);
    }

    @Test
    void When_CompletionSetOnAnnotation_Expect_OverridesConfigDefault() {
        IdempotentOperation operation = resolve("autonomous", JOINED_BY_DEFAULT);

        assertThat(operation.completionMode()).isEqualTo(CompletionMode.AUTONOMOUS);
    }

    @Test
    void When_CompletionUsesRelaxedSpelling_Expect_Resolved() {
        IdempotentOperation operation = resolve("joinTransaction", IdempotencyConfig.defaults());

        assertThat(operation.completionMode()).isEqualTo(CompletionMode.JOIN_TRANSACTION);
    }

    @Test
    void When_CompletionIsNotAValidMode_Expect_RejectedAtStartup() {
        assertThatThrownBy(() -> resolve("eventually", IdempotencyConfig.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completion")
                .hasMessageContaining("eventually");
    }

    private static IdempotentOperation resolve(String methodName, IdempotencyConfig defaults) {
        Method method = method(methodName);
        return IdempotentOperation.of(method.getAnnotation(Idempotent.class), method, Completions.class, defaults);
    }

    private static Method method(String name) {
        try {
            return Completions.class.getDeclaredMethod(name, String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unused")
    static class Completions {

        @Idempotent(key = "#messageId")
        void unset(String messageId) {
            //noop
        }

        @Idempotent(key = "#messageId", completion = "autonomous")
        void autonomous(String messageId) {
            //noop
        }

        @Idempotent(key = "#messageId", completion = "join-transaction")
        void joinTransaction(String messageId) {
            //noop
        }

        @Idempotent(key = "#messageId", completion = "eventually")
        void eventually(String messageId) {
            //noop
        }
    }
}
