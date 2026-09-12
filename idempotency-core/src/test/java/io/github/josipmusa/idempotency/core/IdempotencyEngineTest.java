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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.josipmusa.idempotency.core.exception.IdempotencyDurabilityException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyFingerprintMismatchException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IdempotencyEngineTest {

    private static final String LEASE_ID = "test-lease-id";

    private IdempotencyStore store;
    private ScheduledExecutorService scheduler;
    private IdempotencyEngine engine;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStore.class);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        engine = new IdempotencyEngine(store, scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static final String SCOPE = "TestScope.action";

    private static IdempotencyIdentity identity(String key) {
        return new IdempotencyIdentity(SCOPE, key);
    }

    private IdempotencyContext defaultContext(String key) {
        return IdempotencyContext.builder(SCOPE, key)
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofSeconds(5))
                .waitTimeout(Duration.ofSeconds(5))
                .fingerprint("a".repeat(64))
                .build();
    }

    private long extendLockCalls() {
        return mockingDetails(store).getInvocations().stream()
                .filter(i -> "extendLock".equals(i.getMethod().getName()))
                .count();
    }

    /**
     * Blocks until the scheduler has run everything due within {@code delay}. The scheduler is
     * single-threaded and ordered by due time, so once this returns no heartbeat that was due
     * earlier can still be pending or in flight.
     */
    private void drainScheduler(Duration delay) throws Exception {
        scheduler.schedule(() -> {}, delay.toMillis(), TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
    }

    /** A codec for a plain string result, so the payload the engine stores is inspectable. */
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

    @Test
    void When_NewKey_Expect_ReturnsExecuted() throws Exception {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        Outcome<Void> outcome = engine.execute(defaultContext("new-key"), () -> {});

        assertThat(outcome).isEqualTo(new Outcome.Executed<Void>(null));
    }

    @Test
    void When_ActionReturnsValue_Expect_ExecutedWithValueAndRecordComplete() throws Exception {
        IdempotencyContext context = defaultContext("value-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        Outcome<String> outcome = engine.execute(context, () -> "charged", STRING_CODEC);

        assertThat(outcome).isEqualTo(new Outcome.Executed<>("charged"));
        verify(store).complete(identity("value-key"), LEASE_ID, STRING_CODEC.encode("charged"), context.ttl());
    }

    @Test
    void When_Duplicate_Expect_ReplayedWithDecodedValue() throws Exception {
        Instant completedAt = Instant.now();
        when(store.tryAcquire(any())).thenReturn(AcquireResult.duplicate(STRING_CODEC.encode("charged"), completedAt));

        Outcome<String> outcome = engine.execute(defaultContext("done-key"), () -> "fresh", STRING_CODEC);

        assertThat(outcome).isEqualTo(new Outcome.Replayed<>("charged", completedAt));
        verify(store, never()).complete(any(), any(), any(), any());
    }

    @Test
    void When_RunnableOverload_Expect_NonePayloadStored() throws Exception {
        IdempotencyContext context = defaultContext("runnable-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        engine.execute(context, () -> {});

        verify(store).complete(identity("runnable-key"), LEASE_ID, Payload.none(), context.ttl());
    }

    @Test
    void When_CompletionFailsWithPropagate_Expect_ExceptionAndOnFailedCompletion() {
        IdempotencyContext context = defaultContext("propagate-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        IdempotencyDurabilityException failure = new IdempotencyDurabilityException("replica did not acknowledge");
        doThrow(failure).when(store).complete(any(), any(), any(), any());
        IdempotencyLifecycleListener listener = mock(IdempotencyLifecycleListener.class);
        IdempotencyEngine propagating = new IdempotencyEngine(
                store,
                scheduler,
                List.of(listener),
                IdempotencyConfig.builder()
                        .completionFailurePolicy(CompletionFailurePolicy.PROPAGATE)
                        .build());

        assertThatThrownBy(() -> propagating.execute(context, () -> "charged", STRING_CODEC))
                .isSameAs(failure);

        verify(listener).onFailed(context, LEASE_ID, failure, IdempotencyLifecycleListener.FailurePhase.COMPLETION);
        verify(listener, never()).onCompleted(any(), any(), any());
        verify(store, never()).release(any(), any());
    }

    @Test
    void When_CompletionFailsWithLogAndReturn_Expect_ExecutedAndOnFailedCompletion() throws Exception {
        IdempotencyContext context = defaultContext("log-and-return-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        IdempotencyDurabilityException failure = new IdempotencyDurabilityException("replica did not acknowledge");
        doThrow(failure).when(store).complete(any(), any(), any(), any());
        IdempotencyLifecycleListener listener = mock(IdempotencyLifecycleListener.class);
        IdempotencyEngine lenient = new IdempotencyEngine(
                store,
                scheduler,
                List.of(listener),
                IdempotencyConfig.builder()
                        .completionFailurePolicy(CompletionFailurePolicy.LOG_AND_RETURN)
                        .build());

        Outcome<String> outcome = lenient.execute(context, () -> "charged", STRING_CODEC);

        assertThat(outcome).isEqualTo(new Outcome.Executed<>("charged"));
        verify(listener).onFailed(context, LEASE_ID, failure, IdempotencyLifecycleListener.FailurePhase.COMPLETION);
        verify(listener, never()).onCompleted(any(), any(), any());
        verify(store, never()).release(any(), any());
    }

    @Test
    void When_EncodingFails_Expect_TreatedAsCompletionFailure() {
        IdempotencyContext context = defaultContext("encode-fail-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        IllegalStateException failure = new IllegalStateException("cannot encode");
        PayloadCodec<String> broken = new PayloadCodec<>() {
            @Override
            public Payload encode(String value) {
                throw failure;
            }

            @Override
            public String decode(Payload payload) {
                throw new UnsupportedOperationException();
            }
        };

        assertThatThrownBy(() -> engine.execute(context, () -> "charged", broken))
                .isSameAs(failure);

        verify(store, never()).complete(any(), any(), any(), any());
        verify(store, never()).release(any(), any());
    }

    @Test
    void When_CompletedKey_Expect_ActionNotCalled() throws Exception {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.duplicate(Payload.none(), Instant.now()));
        AtomicInteger counter = new AtomicInteger(0);

        engine.execute(defaultContext("dup-key"), counter::incrementAndGet);

        assertThat(counter.get()).isZero();
    }

    @Test
    void When_ActionThrows_Expect_ReleaseIsCalled() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        String key = "fail-key";

        catchThrowable(() -> engine.execute(defaultContext(key), () -> {
            throw new RuntimeException("boom");
        }));

        verify(store, times(1)).release(identity(key), LEASE_ID);
    }

    @Test
    void When_ActionThrows_Expect_CompleteNeverCalled() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        catchThrowable(() -> engine.execute(defaultContext("fail-key"), () -> {
            throw new RuntimeException("boom");
        }));

        verify(store, never()).complete(any(), any(), any(), any());
    }

    @Test
    void When_ActionThrows_Expect_OriginalExceptionPropagates() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        RuntimeException expected = new RuntimeException("specific failure");

        assertThatThrownBy(() -> engine.execute(defaultContext("fail-key"), () -> {
                    throw expected;
                }))
                .isSameAs(expected);
    }

    @Test
    void When_InFlight_Expect_InFlightOutcomeAndActionNotRun() throws Exception {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.inFlight(Duration.ofSeconds(3)));
        AtomicInteger actionCalls = new AtomicInteger();

        Outcome<Void> outcome = engine.execute(defaultContext("test-key"), actionCalls::incrementAndGet);

        assertThat(outcome).isEqualTo(new Outcome.InFlight<Void>(Duration.ofSeconds(3)));
        assertThat(actionCalls).hasValue(0);
    }

    @Test
    void When_LeaseTenSeconds_Expect_HeartbeatAtFiveSeconds() throws Exception {
        ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
        when(mockScheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
                .thenReturn(mock(ScheduledFuture.class));
        IdempotencyEngine engineOnMockScheduler = new IdempotencyEngine(store, mockScheduler);
        IdempotencyContext context = IdempotencyContext.builder(SCOPE, "hb-interval-key")
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofSeconds(10))
                .build();
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        engineOnMockScheduler.execute(context, () -> {});

        ArgumentCaptor<Long> initialDelay = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> period = ArgumentCaptor.forClass(Long.class);
        verify(mockScheduler)
                .scheduleAtFixedRate(any(), initialDelay.capture(), period.capture(), eq(TimeUnit.MILLISECONDS));
        assertThat(initialDelay.getValue()).isEqualTo(5_000L);
        assertThat(period.getValue()).isEqualTo(5_000L);
    }

    @Test
    void When_LongRunningAction_Expect_HeartbeatExtendsLease() throws Exception {
        IdempotencyContext context = IdempotencyContext.builder(SCOPE, "hb-key")
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofMillis(100))
                .fingerprint("a".repeat(64))
                .build();
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        CountDownLatch beats = new CountDownLatch(1);
        doAnswer(invocation -> {
                    beats.countDown();
                    return null;
                })
                .when(store)
                .extendLock(any(), any(), any());

        // The action outlives the first heartbeat rather than a fixed sleep, so the test is
        // deterministic regardless of how slow the machine running it is.
        engine.execute(
                context, () -> assertThat(beats.await(5, TimeUnit.SECONDS)).isTrue());
        drainScheduler(Duration.ZERO);

        verify(store, atLeastOnce()).extendLock(identity("hb-key"), LEASE_ID, Duration.ofMillis(100));
    }

    @Test
    void When_ActionCompletes_Expect_HeartbeatStops() throws Exception {
        IdempotencyContext context = IdempotencyContext.builder(SCOPE, "hb-stop-key")
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofMillis(100))
                .fingerprint("a".repeat(64))
                .build();
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        engine.execute(context, () -> {});

        // Let any in-flight heartbeat finish, then take the count
        drainScheduler(Duration.ZERO);
        long countAfterExecute = extendLockCalls();

        // Give the scheduler 4x the heartbeat interval (50ms for a 100ms lease) to fire again
        drainScheduler(Duration.ofMillis(200));

        assertThat(extendLockCalls()).isEqualTo(countAfterExecute);
    }

    @Test
    void When_ActionThrows_Expect_HeartbeatStops() throws Exception {
        IdempotencyContext context = IdempotencyContext.builder(SCOPE, "hb-throw-key")
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofMillis(100))
                .fingerprint("a".repeat(64))
                .build();
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        catchThrowable(() -> engine.execute(context, () -> {
            throw new RuntimeException("fail");
        }));

        drainScheduler(Duration.ZERO);
        long countAfterExecute = extendLockCalls();

        drainScheduler(Duration.ofMillis(200));

        assertThat(extendLockCalls()).isEqualTo(countAfterExecute);
    }

    @Test
    void When_FingerprintMismatch_Expect_ThrowsFingerprintMismatchException() {
        IdempotencyContext context = defaultContext("fp-mismatch-key");
        when(store.tryAcquire(context)).thenReturn(AcquireResult.fingerprintMismatch("stored-hash", "received-hash"));

        assertThatThrownBy(() -> engine.execute(context, () -> {}))
                .isInstanceOf(IdempotencyFingerprintMismatchException.class)
                .hasMessageContaining(context.identity().maskedKey())
                .hasMessageNotContaining("fp-mismatch-key")
                .hasMessageContaining("stored-hash")
                .hasMessageContaining("received-hash");
    }

    // --- Context forwarding ---

    @Test
    void When_Execute_Expect_ContextForwardedToStore() throws Exception {
        IdempotencyContext context = defaultContext("forwarded-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        engine.execute(context, () -> {});

        verify(store).tryAcquire(context);
    }

    // --- Checked exception propagation ---

    @Test
    void When_ActionThrowsCheckedException_Expect_PropagatesUnwrapped() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        IOException expected = new IOException("disk full");

        assertThatThrownBy(() -> engine.execute(defaultContext("checked-key"), () -> {
                    throw expected;
                }))
                .isSameAs(expected);
    }

    // --- Cascading failure: release throws after action failure ---

    @Test
    void When_ActionThrowsAndReleaseFails_Expect_OriginalExceptionPropagates() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        RuntimeException actionException = new RuntimeException("action failed");
        IdempotencyStoreException releaseException = new IdempotencyStoreException("store unreachable");
        doThrow(releaseException).when(store).release(any(), any());

        assertThatThrownBy(() -> engine.execute(defaultContext("cascade-key"), () -> {
                    throw actionException;
                }))
                .isSameAs(actionException)
                .satisfies(e -> assertThat(e.getSuppressed()).contains(releaseException));
    }

    // --- Store failure on tryAcquire ---

    @Test
    void When_StoreThrowsOnTryAcquire_Expect_PropagatesDirectly() {
        IdempotencyStoreException storeFailure = new IdempotencyStoreException("connection refused");
        when(store.tryAcquire(any())).thenThrow(storeFailure);

        assertThatThrownBy(() -> engine.execute(defaultContext("store-fail-key"), () -> {}))
                .isSameAs(storeFailure);

        // Heartbeat should never have started
        verify(store, never()).extendLock(any(), any(), any());
    }

    @Test
    void When_HeartbeatExtendLockThrows_Expect_HeartbeatContinues() throws Exception {
        IdempotencyContext context = IdempotencyContext.builder(SCOPE, "hb-error-key")
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofMillis(100))
                .fingerprint("a".repeat(64))
                .build();
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        CountDownLatch beats = new CountDownLatch(2);
        doAnswer(invocation -> {
                    beats.countDown();
                    throw new IdempotencyStoreException("connection lost");
                })
                .when(store)
                .extendLock(any(), any(), any());

        engine.execute(
                context, () -> assertThat(beats.await(5, TimeUnit.SECONDS)).isTrue());
        drainScheduler(Duration.ZERO);

        // Heartbeat should have been called multiple times despite throwing each time
        verify(store, atLeast(2)).extendLock(identity("hb-error-key"), LEASE_ID, Duration.ofMillis(100));
    }

    @Test
    void When_HeartbeatCannotBeScheduled_Expect_LeaseReleasedAndActionNotRun() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        scheduler.shutdownNow();
        AtomicInteger actionCalls = new AtomicInteger();

        assertThatThrownBy(() -> engine.execute(defaultContext("scheduler-rejected"), actionCalls::incrementAndGet))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(actionCalls).hasValue(0);
        verify(store).release(identity("scheduler-rejected"), LEASE_ID);
    }

    @Test
    void When_ActionSucceeds_Expect_CompleteUsesContextTtlAndLease() throws Exception {
        IdempotencyContext context = defaultContext("complete-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        engine.execute(context, () -> "charged", STRING_CODEC);

        verify(store).complete(identity("complete-key"), LEASE_ID, STRING_CODEC.encode("charged"), context.ttl());
    }

    @Test
    void When_StoreCompleteThrows_Expect_ExceptionPropagatesUnchanged() {
        IdempotencyContext context = defaultContext("durability-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        IdempotencyDurabilityException failure = new IdempotencyDurabilityException("replica did not acknowledge");
        doThrow(failure).when(store).complete(any(), any(), any(), any());

        assertThatThrownBy(() -> engine.execute(context, () -> "charged", STRING_CODEC))
                .isSameAs(failure);
    }

    @Test
    void When_NullCodec_Expect_Rejected() {
        assertThatThrownBy(() -> engine.execute(defaultContext("null-codec-key"), () -> "x", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("codec");
    }

    @Test
    void When_NullListeners_Expect_Rejected() {
        assertThatThrownBy(() -> new IdempotencyEngine(store, scheduler, (List<IdempotencyLifecycleListener>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("listeners");
    }
}
