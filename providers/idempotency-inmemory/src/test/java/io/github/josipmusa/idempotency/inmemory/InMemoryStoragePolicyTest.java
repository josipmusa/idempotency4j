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
package io.github.josipmusa.idempotency.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyEngine;
import io.github.josipmusa.idempotency.core.Outcome;
import io.github.josipmusa.idempotency.core.Payload;
import io.github.josipmusa.idempotency.core.PayloadCodec;
import io.github.josipmusa.idempotency.core.exception.IdempotencyFingerprintMismatchException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A declined result frees the key in a real store: the retry runs again, even with a different
 * request body, where a stored result would have been replayed or refused as a mismatch.
 */
class InMemoryStoragePolicyTest {

    private static final PayloadCodec<String> STRING_CODEC = new PayloadCodec<>() {
        @Override
        public Payload encode(String value) {
            return new Payload("text/plain", value.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        @Override
        public String decode(Payload payload) {
            return new String(payload.body(), StandardCharsets.UTF_8);
        }
    };

    private ScheduledExecutorService scheduler;
    private IdempotencyEngine engine;
    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        engine = new IdempotencyEngine(new InMemoryIdempotencyStore(), scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static IdempotencyContext context(String fingerprint) {
        return IdempotencyContext.builder("PaymentController.create", "key-1")
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofSeconds(5))
                .waitTimeout(Duration.ZERO)
                .fingerprint(fingerprint)
                .build();
    }

    private Outcome<String> run(String fingerprint, String result, boolean store) throws Exception {
        return engine.execute(
                context(fingerprint),
                () -> {
                    runs.incrementAndGet();
                    return result;
                },
                STRING_CODEC,
                value -> store);
    }

    @Test
    void When_DeclinedThenRetriedWithACorrectedBody_Expect_RetryRunsAndIsStored() throws Exception {
        String invalidBody = "a".repeat(64);
        String correctedBody = "b".repeat(64);

        assertThat(run(invalidBody, "400 invalid", false)).isEqualTo(new Outcome.Executed<>("400 invalid"));
        assertThat(run(correctedBody, "201 created", true)).isEqualTo(new Outcome.Executed<>("201 created"));

        Outcome<String> duplicate = run(correctedBody, "never", true);
        assertThat(duplicate).isInstanceOf(Outcome.Replayed.class);
        assertThat(((Outcome.Replayed<String>) duplicate).value()).isEqualTo("201 created");
        assertThat(runs).hasValue(2);
    }

    @Test
    void When_StoredThenRetriedWithAnotherBody_Expect_Mismatch() throws Exception {
        run("a".repeat(64), "400 invalid", true);

        assertThatThrownBy(() -> run("b".repeat(64), "201 created", true))
                .isInstanceOf(IdempotencyFingerprintMismatchException.class);
        assertThat(runs).hasValue(1);
    }

    @Test
    void When_DeclinedThenRetriedWithTheSameBody_Expect_RunsAgain() throws Exception {
        String body = "a".repeat(64);

        run(body, "503 downstream unreachable", false);
        assertThat(run(body, "201 created", true)).isEqualTo(new Outcome.Executed<>("201 created"));

        assertThat(runs).hasValue(2);
    }
}
