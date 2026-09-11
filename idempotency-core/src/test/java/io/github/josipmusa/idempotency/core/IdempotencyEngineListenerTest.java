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
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

import io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener.FailurePhase;
import io.github.josipmusa.idempotency.core.exception.IdempotencyDurabilityException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Lifecycle callback contract: which callbacks fire on each path, in what order, with what
 * arguments, and the guarantee that a misbehaving listener cannot change any of it.
 */
class IdempotencyEngineListenerTest {

    private static final String LEASE_ID = "test-lease-id";

    private IdempotencyStore store;
    private ScheduledExecutorService scheduler;
    private IdempotencyLifecycleListener listener;
    private IdempotencyEngine engine;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStore.class);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        listener = mock(IdempotencyLifecycleListener.class);
        engine = new IdempotencyEngine(store, scheduler, List.of(listener));
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

    private StoredResponse anyStoredResponse() {
        return new StoredResponse(
                200, Map.of("Content-Type", List.of("application/json")), "{\"id\":\"123\"}".getBytes(), Instant.now());
    }

    @Test
    void When_ActionSucceedsAndCompletes_Expect_AcquiredThenCompleted() throws Exception {
        IdempotencyContext context = defaultContext("happy-key");
        StoredResponse payload = anyStoredResponse();
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        ExecutionResult result = engine.execute(context, () -> {});
        engine.complete(context, ((ExecutionResult.Executed) result).leaseId(), payload, context.ttl());

        InOrder inOrder = inOrder(listener);
        inOrder.verify(listener).onAcquired(context, LEASE_ID);
        inOrder.verify(listener).onCompleted(context, LEASE_ID, payload);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void When_ActionSucceeds_Expect_AcquiredFiredBeforeAction() throws Exception {
        IdempotencyContext context = defaultContext("before-action-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        List<String> events = new ArrayList<>();
        doAnswer(invocation -> events.add("onAcquired")).when(listener).onAcquired(any(), any());

        engine.execute(context, () -> events.add("action"));

        assertThat(events).containsExactly("onAcquired", "action");
    }

    @Test
    void When_ActionThrows_Expect_FailedWithActionPhaseAfterRelease() {
        IdempotencyContext context = defaultContext("action-fail-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        RuntimeException actionFailure = new RuntimeException("boom");

        assertThatThrownBy(() -> engine.execute(context, () -> {
                    throw actionFailure;
                }))
                .isSameAs(actionFailure);

        InOrder inOrder = inOrder(listener, store);
        inOrder.verify(listener).onAcquired(context, LEASE_ID);
        inOrder.verify(store).release(identity("action-fail-key"), LEASE_ID);
        inOrder.verify(listener).onFailed(context, LEASE_ID, actionFailure, FailurePhase.ACTION);
        verify(listener, never()).onCompleted(any(), any(), any());
    }

    @Test
    void When_ActionThrowsAndReleaseFails_Expect_FailedStillFiredWithSuppressedRelease() {
        IdempotencyContext context = defaultContext("release-fail-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        RuntimeException actionFailure = new RuntimeException("boom");
        IdempotencyStoreException releaseFailure = new IdempotencyStoreException("store unreachable");
        doThrow(releaseFailure).when(store).release(any(), any());

        assertThatThrownBy(() -> engine.execute(context, () -> {
                    throw actionFailure;
                }))
                .isSameAs(actionFailure);

        verify(listener).onFailed(context, LEASE_ID, actionFailure, FailurePhase.ACTION);
        assertThat(actionFailure.getSuppressed()).contains(releaseFailure);
    }

    @Test
    void When_CompleteFails_Expect_FailedWithCompletionPhase() throws Exception {
        IdempotencyContext context = defaultContext("completion-fail-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        IdempotencyDurabilityException failure = new IdempotencyDurabilityException("replica did not acknowledge");
        doThrow(failure).when(store).complete(any(), any(), any(), any());

        engine.execute(context, () -> {});
        assertThatThrownBy(() -> engine.complete(context, LEASE_ID, anyStoredResponse(), context.ttl()))
                .isSameAs(failure);

        InOrder inOrder = inOrder(listener);
        inOrder.verify(listener).onAcquired(context, LEASE_ID);
        inOrder.verify(listener).onFailed(context, LEASE_ID, failure, FailurePhase.COMPLETION);
        verify(listener, never()).onCompleted(any(), any(), any());
    }

    @Test
    void When_DuplicateWithStoredResponse_Expect_OnDuplicateOnly() throws Exception {
        IdempotencyContext context = defaultContext("duplicate-key");
        StoredResponse stored = anyStoredResponse();
        when(store.tryAcquire(any())).thenReturn(AcquireResult.duplicate(stored));

        engine.execute(context, () -> {});

        verify(listener).onDuplicate(context, stored);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void When_DuplicateWithNoPayload_Expect_NoPayloadForwarded() throws Exception {
        IdempotencyContext context = IdempotencyContext.builder(SCOPE, "duplicate-no-payload")
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofSeconds(5))
                .build();
        NoPayload stored = NoPayload.at(Instant.now());
        when(store.tryAcquire(any())).thenReturn(AcquireResult.duplicate(stored));

        engine.execute(context, () -> {});

        verify(listener).onDuplicate(context, stored);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void When_DuplicateListenerThrows_Expect_DuplicateStillReturned() throws Exception {
        StoredResponse stored = anyStoredResponse();
        when(store.tryAcquire(any())).thenReturn(AcquireResult.duplicate(stored));
        doThrow(new RuntimeException("listener boom")).when(listener).onDuplicate(any(), any());

        ExecutionResult result = engine.execute(defaultContext("duplicate-throwing-key"), () -> {});

        assertThat(result).isInstanceOf(ExecutionResult.Duplicate.class);
        assertThat(((ExecutionResult.Duplicate) result).payload()).isSameAs(stored);
    }

    @Test
    void When_InFlight_Expect_NoCallbacks() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.inFlight(Duration.ofSeconds(3)));

        assertThatThrownBy(() -> engine.execute(defaultContext("timeout-key"), () -> {}))
                .isNotNull();

        verifyNoInteractions(listener);
    }

    @Test
    void When_FingerprintMismatch_Expect_NoCallbacks() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.fingerprintMismatch("stored-hash", "received-hash"));

        assertThatThrownBy(() -> engine.execute(defaultContext("mismatch-key"), () -> {}))
                .isNotNull();

        verifyNoInteractions(listener);
    }

    /**
     * A lease whose heartbeat cannot start is released before the action runs, so no listener ever
     * hears about it. Firing {@code onAcquired} here would leave a listener holding per-request
     * state with no terminal callback to release it.
     */
    @Test
    void When_HeartbeatCannotBeScheduled_Expect_NoCallbacks() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        scheduler.shutdownNow();

        assertThatThrownBy(() -> engine.execute(defaultContext("scheduler-rejected"), () -> {}))
                .isNotNull();

        verifyNoInteractions(listener);
    }

    @Test
    void When_AcquiredListenerThrows_Expect_ActionStillRunsAndResultUnchanged() throws Exception {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        doThrow(new RuntimeException("listener boom")).when(listener).onAcquired(any(), any());
        AtomicInteger actionCalls = new AtomicInteger();

        ExecutionResult result = engine.execute(defaultContext("throwing-acquired-key"), actionCalls::incrementAndGet);

        assertThat(actionCalls).hasValue(1);
        assertThat(result).isInstanceOf(ExecutionResult.Executed.class);
    }

    @Test
    void When_CompletedListenerThrows_Expect_CompleteReturnsNormally() {
        IdempotencyContext context = defaultContext("throwing-completed-key");
        StoredResponse payload = anyStoredResponse();
        doThrow(new RuntimeException("listener boom")).when(listener).onCompleted(any(), any(), any());

        engine.complete(context, LEASE_ID, payload, context.ttl());

        verify(store).complete(identity("throwing-completed-key"), LEASE_ID, payload, context.ttl());
    }

    @Test
    void When_FailedListenerThrows_Expect_OriginalExceptionPropagatesUntouched() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        doThrow(new RuntimeException("listener boom")).when(listener).onFailed(any(), any(), any(), any());
        RuntimeException actionFailure = new RuntimeException("boom");

        assertThatThrownBy(() -> engine.execute(defaultContext("throwing-failed-key"), () -> {
                    throw actionFailure;
                }))
                .isSameAs(actionFailure)
                .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
    }

    @Test
    void When_MultipleListeners_Expect_CalledInRegistrationOrder() throws Exception {
        IdempotencyLifecycleListener first = mock(IdempotencyLifecycleListener.class);
        IdempotencyLifecycleListener second = mock(IdempotencyLifecycleListener.class);
        engine = new IdempotencyEngine(store, scheduler, List.of(first, second));
        IdempotencyContext context = defaultContext("ordered-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        engine.execute(context, () -> {});

        InOrder inOrder = inOrder(first, second);
        inOrder.verify(first).onAcquired(context, LEASE_ID);
        inOrder.verify(second).onAcquired(context, LEASE_ID);
    }

    @Test
    void When_FirstListenerThrows_Expect_RemainingListenersStillCalled() throws Exception {
        IdempotencyLifecycleListener failing = mock(IdempotencyLifecycleListener.class);
        IdempotencyLifecycleListener healthy = mock(IdempotencyLifecycleListener.class);
        doThrow(new RuntimeException("listener boom")).when(failing).onAcquired(any(), any());
        engine = new IdempotencyEngine(store, scheduler, List.of(failing, healthy));
        IdempotencyContext context = defaultContext("resilient-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        engine.execute(context, () -> {});

        verify(healthy).onAcquired(context, LEASE_ID);
    }

    @Test
    void When_OnAcquired_Expect_CalledOnCallerThread() throws Exception {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        doAnswer(invocation -> callbackThread.getAndSet(Thread.currentThread()))
                .when(listener)
                .onAcquired(any(), any());

        engine.execute(defaultContext("thread-key"), () -> {});

        assertThat(callbackThread).hasValue(Thread.currentThread());
    }

    @Test
    void When_ListenerListMutatedAfterConstruction_Expect_EngineUnaffected() throws Exception {
        List<IdempotencyLifecycleListener> mutable = new ArrayList<>(List.of(listener));
        engine = new IdempotencyEngine(store, scheduler, mutable);
        mutable.clear();
        IdempotencyContext context = defaultContext("defensive-copy-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));

        engine.execute(context, () -> {});

        verify(listener).onAcquired(context, LEASE_ID);
    }

    @Test
    void When_NoListenersRegistered_Expect_ExecutionUnaffected() throws Exception {
        engine = new IdempotencyEngine(store, scheduler);
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        AtomicInteger actionCalls = new AtomicInteger();

        ExecutionResult result = engine.execute(defaultContext("no-listener-key"), actionCalls::incrementAndGet);

        assertThat(actionCalls).hasValue(1);
        assertThat(result).isInstanceOf(ExecutionResult.Executed.class);
    }

    @Test
    void When_ActionThrowsCheckedException_Expect_FailedCarriesThatCause() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        IdempotencyContext context = defaultContext("checked-key");
        Exception actionFailure = new Exception("checked boom");

        assertThatThrownBy(() -> engine.execute(context, () -> {
                    throw actionFailure;
                }))
                .isSameAs(actionFailure);

        verify(listener).onFailed(eq(context), eq(LEASE_ID), same(actionFailure), eq(FailurePhase.ACTION));
    }
}
