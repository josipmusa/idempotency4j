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

import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * {@code tryAcquire} answers to its wait budget on every path round the loop.
 *
 * <p>The steal branch retries with {@code continue} when it loses its race for an expired
 * lease, and that retry used to skip the deadline check entirely: the only accounting lived
 * in the branch for an active lease, which a stolen-lease retry never reaches. A caller that
 * asked not to wait could then go round again, and again, with nothing counting it.
 */
class InMemoryAcquireWaitBudgetTest {

    private static final String SCOPE = "WaitBudget.contend";
    private static final String KEY = "contended";

    @Test
    void When_ZeroWaitUnderExpiredLeaseContention_Expect_ReturnsPromptly() throws Exception {
        // A lease that is expired the moment it is taken keeps every caller in the steal
        // branch, which is the branch that lost its deadline check.
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService churn = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 4; i++) {
                churn.submit(() -> {
                    while (!stop.get()) {
                        store.tryAcquire(context(Duration.ofMillis(2), Duration.ZERO));
                    }
                });
            }

            List<Future<AcquireResult>> attempts = new ArrayList<>();
            ExecutorService callers = Executors.newFixedThreadPool(4);
            try {
                for (int i = 0; i < 16; i++) {
                    attempts.add(callers.submit(() -> store.tryAcquire(context(Duration.ofMillis(2), Duration.ZERO))));
                }
                for (Future<AcquireResult> attempt : attempts) {
                    // Each caller asked not to wait, so each must resolve rather than spin
                    // while the churn keeps handing it a freshly expired lease.
                    assertThat(attempt.get(10, TimeUnit.SECONDS)).isNotNull();
                }
            } finally {
                callers.shutdownNow();
            }
        } finally {
            stop.set(true);
            churn.shutdownNow();
            assertThat(churn.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void When_WaitBudgetElapses_Expect_InFlightRatherThanAnotherPass() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.tryAcquire(context(Duration.ofMinutes(5), Duration.ZERO));

        long startedAt = System.nanoTime();
        AcquireResult result = store.tryAcquire(context(Duration.ofMinutes(5), Duration.ofMillis(200)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(result).isInstanceOf(AcquireResult.InFlight.class);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    private static IdempotencyContext context(Duration lease, Duration wait) {
        return IdempotencyContext.builder(SCOPE, KEY)
                .ttl(Duration.ofMinutes(5))
                .leaseDuration(lease)
                .waitTimeout(wait)
                .build();
    }
}
