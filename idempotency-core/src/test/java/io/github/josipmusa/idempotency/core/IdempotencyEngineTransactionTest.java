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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.josipmusa.idempotency.core.exception.IdempotencyRollbackException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CompletionMode#JOIN_TRANSACTION}: the completion rides the caller's transaction, and
 * so does the terminal callback.
 */
class IdempotencyEngineTransactionTest {

    private static final String SCOPE = "TransactionScope.action";
    private static final String LEASE_ID = "lease-1";

    private IdempotencyStore store;
    private ScheduledExecutorService scheduler;
    private FakeTransaction transaction;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStore.class);
        when(store.supportsTransactionalCompletion()).thenReturn(true);
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        scheduler = Executors.newSingleThreadScheduledExecutor();
        transaction = new FakeTransaction();
        listener = new RecordingListener();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private IdempotencyEngine engine(IdempotencyStore backing, TransactionParticipation participation) {
        return new IdempotencyEngine(
                backing, scheduler, List.of(listener), IdempotencyConfig.defaults(), participation);
    }

    private static IdempotencyContext joinedContext(String key) {
        return IdempotencyContext.builder(SCOPE, key)
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofSeconds(5))
                .waitTimeout(Duration.ZERO)
                .completionMode(CompletionMode.JOIN_TRANSACTION)
                .build();
    }

    @Test
    void When_JoinedModeAndCommit_Expect_OnCompletedAfterCommitOnly() throws Exception {
        IdempotencyContext context = joinedContext("commit-key");
        transaction.begin();

        Outcome<Void> outcome = engine(store, transaction).execute(context, () -> {});

        assertThat(outcome).isInstanceOf(Outcome.Executed.class);
        verify(store).complete(eq(context.identity()), eq(LEASE_ID), eq(Payload.none()), eq(context.ttl()));
        assertThat(listener.events).containsExactly("onAcquired");

        transaction.commit();

        assertThat(listener.events).containsExactly("onAcquired", "onCompleted");
        verify(store, never()).release(any(), any());
    }

    @Test
    void When_JoinedModeAndRollback_Expect_ReleasedAndOnFailedRollback() throws Exception {
        IdempotencyContext context = joinedContext("rollback-key");
        transaction.begin();

        engine(store, transaction).execute(context, () -> {});
        assertThat(listener.events).containsExactly("onAcquired");

        transaction.rollback();

        assertThat(listener.events).containsExactly("onAcquired", "onFailed:ROLLBACK");
        assertThat(listener.lastCause).isInstanceOf(IdempotencyRollbackException.class);
        verify(store).release(context.identity(), LEASE_ID);
    }

    @Test
    void When_JoinedModeWithoutActiveTransaction_Expect_IllegalState() {
        IdempotencyContext context = joinedContext("no-tx-key");

        assertThatThrownBy(() -> engine(store, transaction).execute(context, () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JOIN_TRANSACTION");

        verify(store, never()).tryAcquire(any());
        assertThat(listener.events).isEmpty();
    }

    @Test
    void When_JoinedModeOnNonTransactionalStore_Expect_RejectedAtConstruction() {
        IdempotencyStore plainStore = mock(IdempotencyStore.class);

        assertThatThrownBy(() -> engine(plainStore, transaction))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support transactional completion");
    }

    @Test
    void When_AutonomousModeOnTransactionalEngine_Expect_OnCompletedImmediately() throws Exception {
        IdempotencyContext context = IdempotencyContext.builder(SCOPE, "autonomous-key")
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofSeconds(5))
                .waitTimeout(Duration.ZERO)
                .build();
        transaction.begin();

        engine(store, transaction).execute(context, () -> {});

        assertThat(listener.events).containsExactly("onAcquired", "onCompleted");
        assertThat(transaction.afterCommit).isEmpty();
    }

    /** Records callbacks so a test can assert the order the engine fired them in. */
    private static final class RecordingListener implements IdempotencyLifecycleListener {

        private final List<String> events = new ArrayList<>();
        private Throwable lastCause;

        @Override
        public void onAcquired(IdempotencyContext ctx, String leaseId) {
            events.add("onAcquired");
        }

        @Override
        public void onCompleted(IdempotencyContext ctx, String leaseId, Payload payload) {
            events.add("onCompleted");
        }

        @Override
        public void onFailed(IdempotencyContext ctx, String leaseId, Throwable cause, FailurePhase phase) {
            events.add("onFailed:" + phase);
            lastCause = cause;
        }
    }

    /**
     * A transaction the test drives by hand: {@code begin} makes it active, and
     * {@code commit}/{@code rollback} run the callbacks the engine registered, exactly as a
     * real transaction manager would once the outcome is known.
     */
    private static final class FakeTransaction implements TransactionParticipation {

        private final List<Runnable> afterCommit = new ArrayList<>();
        private final List<Runnable> afterRollback = new ArrayList<>();
        private boolean active;

        void begin() {
            active = true;
        }

        void commit() {
            active = false;
            afterCommit.forEach(Runnable::run);
        }

        void rollback() {
            active = false;
            afterRollback.forEach(Runnable::run);
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public void afterCommit(Runnable action) {
            afterCommit.add(action);
        }

        @Override
        public void afterRollback(Runnable action) {
            afterRollback.add(action);
        }
    }
}
