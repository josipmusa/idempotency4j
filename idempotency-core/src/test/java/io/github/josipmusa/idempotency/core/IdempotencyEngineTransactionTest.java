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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyRollbackException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

/**
 * How the engine behaves around the caller's transaction. Under
 * {@link CompletionMode#JOIN_TRANSACTION} the completion rides that transaction, and so does the
 * terminal callback. Under {@link CompletionMode#AUTONOMOUS} the completion waits for it: the
 * record is written once the transaction has committed, and a rollback frees the key.
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
        return engine(backing, participation, CompletionFailurePolicy.PROPAGATE);
    }

    private IdempotencyEngine engine(
            IdempotencyStore backing, TransactionParticipation participation, CompletionFailurePolicy policy) {
        IdempotencyConfig config =
                IdempotencyConfig.builder().completionFailurePolicy(policy).build();
        return new IdempotencyEngine(backing, scheduler, List.of(listener), config, participation);
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
        verify(store)
                .completeInTransaction(eq(context.identity()), eq(LEASE_ID), eq(Payload.none()), eq(context.ttl()));
        verify(store, never()).complete(any(), any(), any(), any());
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

    @ParameterizedTest
    @EnumSource(CompletionFailurePolicy.class)
    void When_JoinedCompletionFailsUnderAnyPolicy_Expect_Propagated(CompletionFailurePolicy policy) {
        IdempotencyContext context = joinedContext("failing-complete-key");
        RuntimeException refused = new IdempotencyStoreException("refused");
        doThrow(refused).when(store).completeInTransaction(any(), any(), any(), any());
        transaction.begin();

        assertThatThrownBy(() -> engine(store, transaction, policy).execute(context, () -> {}))
                .isSameAs(refused);

        assertThat(listener.events).containsExactly("onAcquired", "onFailed:COMPLETION");
    }

    @Test
    void When_JoinedCompletionFailsAndTransactionRollsBack_Expect_ReleasedWithoutSecondTerminal() {
        IdempotencyContext context = joinedContext("failing-rollback-key");
        doThrow(new IdempotencyStoreException("refused")).when(store).completeInTransaction(any(), any(), any(), any());
        transaction.begin();

        assertThatThrownBy(() -> engine(store, transaction).execute(context, () -> {}))
                .isInstanceOf(IdempotencyStoreException.class);
        transaction.rollback();

        verify(store).release(context.identity(), LEASE_ID);
        assertThat(listener.events).containsExactly("onAcquired", "onFailed:COMPLETION");
    }

    @Test
    void When_JoinedCompletionFailsAndTransactionCommitsAnyway_Expect_LeaseLeftInPlace() {
        IdempotencyContext context = joinedContext("failing-commit-key");
        doThrow(new IdempotencyStoreException("refused")).when(store).completeInTransaction(any(), any(), any(), any());
        transaction.begin();

        assertThatThrownBy(() -> engine(store, transaction).execute(context, () -> {}))
                .isInstanceOf(IdempotencyStoreException.class);
        transaction.commit();

        verify(store, never()).release(any(), any());
        assertThat(listener.events).containsExactly("onAcquired", "onFailed:COMPLETION");
    }

    /**
     * The completion was refused because another caller stole the lease. After the rollback that
     * caller owns the key, so there is nothing to release and nothing to warn about.
     */
    @Test
    void When_JoinedCompletionLostItsLeaseAndTransactionRollsBack_Expect_NoWarning() {
        IdempotencyContext context = joinedContext("stolen-rollback-key");
        doThrow(new IdempotencyLeaseLostException("stolen"))
                .when(store)
                .completeInTransaction(any(), any(), any(), any());
        doThrow(new IdempotencyLeaseLostException("stolen")).when(store).release(any(), any());
        transaction.begin();
        Logger engineLogger = (Logger) LoggerFactory.getLogger(IdempotencyEngine.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        engineLogger.setLevel(Level.DEBUG);
        engineLogger.addAppender(logs);
        try {
            assertThatThrownBy(() -> engine(store, transaction).execute(context, () -> {}))
                    .isInstanceOf(IdempotencyLeaseLostException.class);
            transaction.rollback();
        } finally {
            engineLogger.detachAppender(logs);
            engineLogger.setLevel(Level.OFF);
        }

        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
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
    void When_JoinedModeOnNonTransactionalStore_Expect_IllegalStateBeforeAcquire() {
        IdempotencyStore plainStore = mock(IdempotencyStore.class);
        IdempotencyContext context = joinedContext("plain-store-key");
        transaction.begin();

        assertThatThrownBy(() -> engine(plainStore, transaction).execute(context, () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot complete inside a caller's transaction");

        verify(plainStore, never()).tryAcquire(any());
    }

    @Test
    void When_AutonomousModeWithoutTransaction_Expect_CompletedImmediately() throws Exception {
        IdempotencyContext context = autonomousContext("autonomous-key", Duration.ofSeconds(5));

        engine(store, transaction).execute(context, () -> {});

        verify(store).complete(eq(context.identity()), eq(LEASE_ID), eq(Payload.none()), eq(context.ttl()));
        assertThat(listener.events).containsExactly("onAcquired", "onCompleted");
        assertThat(transaction.afterCommit).isEmpty();
    }

    @Test
    void When_AutonomousModeInsideTransaction_Expect_CompletedOnlyAfterCommit() throws Exception {
        IdempotencyContext context = autonomousContext("deferred-commit-key", Duration.ofSeconds(5));
        transaction.begin();

        Outcome<Void> outcome = engine(store, transaction).execute(context, () -> {});

        assertThat(outcome).isInstanceOf(Outcome.Executed.class);
        verify(store, never()).complete(any(), any(), any(), any());
        assertThat(listener.events).containsExactly("onAcquired");

        transaction.commit();

        verify(store).complete(eq(context.identity()), eq(LEASE_ID), eq(Payload.none()), eq(context.ttl()));
        verify(store, never()).completeInTransaction(any(), any(), any(), any());
        verify(store, never()).release(any(), any());
        assertThat(listener.events).containsExactly("onAcquired", "onCompleted");
    }

    @Test
    void When_AutonomousModeInsideTransactionAndRollback_Expect_ReleasedAndOnFailedRollback() throws Exception {
        IdempotencyContext context = autonomousContext("deferred-rollback-key", Duration.ofSeconds(5));
        transaction.begin();

        engine(store, transaction).execute(context, () -> {});
        transaction.rollback();

        verify(store, never()).complete(any(), any(), any(), any());
        verify(store).release(context.identity(), LEASE_ID);
        assertThat(listener.events).containsExactly("onAcquired", "onFailed:ROLLBACK");
        assertThat(listener.lastCause).isInstanceOf(IdempotencyRollbackException.class);
    }

    @ParameterizedTest
    @EnumSource(CompletionFailurePolicy.class)
    void When_DeferredCompletionFailsAfterCommit_Expect_OnFailedCompletionAndLeaseKept(CompletionFailurePolicy policy)
            throws Exception {
        IdempotencyContext context = autonomousContext("deferred-failure-key", Duration.ofSeconds(5));
        doThrow(new IdempotencyStoreException("refused")).when(store).complete(any(), any(), any(), any());
        transaction.begin();

        Outcome<Void> outcome = engine(store, transaction, policy).execute(context, () -> {});
        transaction.commit();

        assertThat(outcome).isInstanceOf(Outcome.Executed.class);
        verify(store, never()).release(any(), any());
        assertThat(listener.events).containsExactly("onAcquired", "onFailed:COMPLETION");
    }

    @Test
    void When_DeferredEncodingFails_Expect_CompletionFailureBeforeCommit() {
        IdempotencyContext context = autonomousContext("deferred-encode-key", Duration.ofSeconds(5));
        RuntimeException unencodable = new IllegalStateException("cannot encode");
        PayloadCodec<String> codec = new PayloadCodec<>() {
            @Override
            public Payload encode(String value) {
                throw unencodable;
            }

            @Override
            public String decode(Payload payload) {
                return null;
            }
        };
        transaction.begin();

        assertThatThrownBy(() -> engine(store, transaction).execute(context, () -> "value", codec))
                .isSameAs(unencodable);

        assertThat(listener.events).containsExactly("onAcquired", "onFailed:COMPLETION");
        assertThat(transaction.afterCommit).isEmpty();
        verify(store, never()).release(any(), any());
    }

    @Test
    void When_AutonomousModeInsideTransaction_Expect_HeartbeatAliveUntilCommit() throws Exception {
        AtomicInteger extensions = new AtomicInteger();
        doAnswer(invocation -> extensions.incrementAndGet()).when(store).extendLock(any(), any(), any());
        IdempotencyContext context = autonomousContext("deferred-heartbeat-key", Duration.ofMillis(100));
        transaction.begin();

        engine(store, transaction).execute(context, () -> {});
        int afterReturn = extensions.get();
        Thread.sleep(300);

        assertThat(extensions.get())
                .as("heartbeats while the transaction is still open")
                .isGreaterThan(afterReturn);

        transaction.commit();
        int afterCommit = extensions.get();
        Thread.sleep(300);

        assertThat(extensions.get()).as("heartbeats after the commit").isEqualTo(afterCommit);
    }

    @Test
    void When_AutonomousModeInsideTransactionAndRollback_Expect_HeartbeatStopped() throws Exception {
        AtomicInteger extensions = new AtomicInteger();
        doAnswer(invocation -> extensions.incrementAndGet()).when(store).extendLock(any(), any(), any());
        IdempotencyContext context = autonomousContext("deferred-rollback-heartbeat-key", Duration.ofMillis(100));
        transaction.begin();

        engine(store, transaction).execute(context, () -> {});
        transaction.rollback();
        int afterRollback = extensions.get();
        Thread.sleep(300);

        assertThat(extensions.get()).as("heartbeats after the rollback").isEqualTo(afterRollback);
    }

    @Test
    void When_ActionEndsTheTransactionItself_Expect_CompletedImmediately() throws Exception {
        IdempotencyContext context = autonomousContext("self-committing-key", Duration.ofSeconds(5));

        engine(store, transaction).execute(context, () -> {
            transaction.begin();
            transaction.commit();
        });

        verify(store).complete(eq(context.identity()), eq(LEASE_ID), eq(Payload.none()), eq(context.ttl()));
        assertThat(listener.events).containsExactly("onAcquired", "onCompleted");
    }

    @Test
    void When_NonTransactionalStoreInsideTransaction_Expect_CompletionStillDeferred() throws Exception {
        IdempotencyStore plainStore = mock(IdempotencyStore.class);
        when(plainStore.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        IdempotencyContext context = autonomousContext("plain-deferred-key", Duration.ofSeconds(5));
        transaction.begin();

        engine(plainStore, transaction).execute(context, () -> {});
        verify(plainStore, never()).complete(any(), any(), any(), any());

        transaction.commit();

        verify(plainStore).complete(eq(context.identity()), eq(LEASE_ID), eq(Payload.none()), eq(context.ttl()));
        assertThat(listener.events).containsExactly("onAcquired", "onCompleted");
    }

    private static IdempotencyContext autonomousContext(String key, Duration lease) {
        return IdempotencyContext.builder(SCOPE, key)
                .ttl(Duration.ofHours(1))
                .leaseDuration(lease)
                .waitTimeout(Duration.ZERO)
                .completionMode(CompletionMode.AUTONOMOUS)
                .build();
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
