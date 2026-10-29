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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener.FailurePhase;
import io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A {@link StoragePolicy} that declines a fresh result: the engine releases the lease instead of
 * recording a completion, and still answers {@link Outcome.Executed}.
 */
class IdempotencyEngineStoragePolicyTest {

    private static final String SCOPE = "PolicyScope.action";
    private static final String LEASE_ID = "lease-1";

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

    private IdempotencyStore store;
    private ScheduledExecutorService scheduler;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStore.class);
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired(LEASE_ID));
        scheduler = Executors.newSingleThreadScheduledExecutor();
        listener = new RecordingListener();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private IdempotencyEngine engine(CompletionFailurePolicy policy) {
        IdempotencyConfig config =
                IdempotencyConfig.builder().completionFailurePolicy(policy).build();
        return new IdempotencyEngine(store, scheduler, List.of(listener), config);
    }

    private static IdempotencyContext context(String key, Duration lease) {
        return IdempotencyContext.builder(SCOPE, key)
                .ttl(Duration.ofHours(1))
                .leaseDuration(lease)
                .waitTimeout(Duration.ZERO)
                .build();
    }

    @Test
    void When_PolicyDeclines_Expect_LeaseReleasedNothingStoredAndExecutedReturned() throws Exception {
        IdempotencyContext context = context("declined", Duration.ofSeconds(5));
        AtomicInteger encodes = new AtomicInteger();
        PayloadCodec<String> countingCodec = new PayloadCodec<>() {
            @Override
            public Payload encode(String value) {
                encodes.incrementAndGet();
                return STRING_CODEC.encode(value);
            }

            @Override
            public String decode(Payload payload) {
                return STRING_CODEC.decode(payload);
            }
        };

        Outcome<String> outcome = engine(CompletionFailurePolicy.PROPAGATE)
                .execute(context, () -> "refused before the downstream call", countingCodec, value -> false);

        assertThat(outcome).isEqualTo(new Outcome.Executed<>("refused before the downstream call"));
        verify(store).release(context.identity(), LEASE_ID);
        verify(store, never()).complete(any(), any(), any(), any());
        assertThat(encodes).as("a declined result is never encoded").hasValue(0);
        assertThat(listener.events).containsExactly("onAcquired", "onReleased");
    }

    @Test
    void When_PolicyAccepts_Expect_CompletedAsWithoutPolicy() throws Exception {
        IdempotencyContext context = context("accepted", Duration.ofSeconds(5));

        Outcome<String> outcome = engine(CompletionFailurePolicy.PROPAGATE)
                .execute(context, () -> "created", STRING_CODEC, value -> true);

        assertThat(outcome).isEqualTo(new Outcome.Executed<>("created"));
        verify(store).complete(any(), any(), any(), any());
        verify(store, never()).release(any(), any());
        assertThat(listener.events).containsExactly("onAcquired", "onCompleted");
    }

    @Test
    void When_NoPolicy_Expect_EveryResultStored() throws Exception {
        IdempotencyContext context = context("default", Duration.ofSeconds(5));

        engine(CompletionFailurePolicy.PROPAGATE).execute(context, () -> "created", STRING_CODEC);

        verify(store).complete(any(), any(), any(), any());
        verify(store, never()).release(any(), any());
    }

    @Test
    void When_PolicyGetsTheFreshResult_Expect_ActionValuePassed() throws Exception {
        List<String> seen = new ArrayList<>();

        engine(CompletionFailurePolicy.PROPAGATE)
                .execute(context("seen", Duration.ofSeconds(5)), () -> "fresh", STRING_CODEC, value -> {
                    seen.add(value);
                    return true;
                });

        assertThat(seen).containsExactly("fresh");
    }

    @Test
    void When_PolicyThrowsUnderPropagate_Expect_CompletionFailureAndRecordKept() {
        IdempotencyContext context = context("policy-throws", Duration.ofSeconds(5));
        IllegalStateException broken = new IllegalStateException("policy broke");

        assertThatThrownBy(() -> engine(CompletionFailurePolicy.PROPAGATE)
                        .execute(context, () -> "work done", STRING_CODEC, value -> {
                            throw broken;
                        }))
                .isSameAs(broken);

        verify(store, never()).release(any(), any());
        verify(store, never()).complete(any(), any(), any(), any());
        assertThat(listener.events).containsExactly("onAcquired", "onFailed:COMPLETION");
        assertThat(listener.lastCause).isSameAs(broken);
    }

    @Test
    void When_PolicyThrowsUnderLogAndReturn_Expect_ExecutedAndRecordKept() throws Exception {
        IdempotencyContext context = context("policy-throws-logged", Duration.ofSeconds(5));

        Outcome<String> outcome = engine(CompletionFailurePolicy.LOG_AND_RETURN)
                .execute(context, () -> "work done", STRING_CODEC, value -> {
                    throw new IllegalStateException("policy broke");
                });

        assertThat(outcome).isEqualTo(new Outcome.Executed<>("work done"));
        verify(store, never()).release(any(), any());
        assertThat(listener.events).containsExactly("onAcquired", "onFailed:COMPLETION");
    }

    @Test
    void When_PolicyThrowsAnError_Expect_PropagatedAndRecordKept() {
        IdempotencyContext context = context("policy-error", Duration.ofSeconds(5));
        AssertionError error = new AssertionError("policy error");

        assertThatThrownBy(() -> engine(CompletionFailurePolicy.LOG_AND_RETURN)
                        .execute(context, () -> "work done", STRING_CODEC, value -> {
                            throw error;
                        }))
                .isSameAs(error);

        verify(store, never()).release(any(), any());
        assertThat(listener.events).containsExactly("onAcquired", "onFailed:COMPLETION");
    }

    @ParameterizedTest
    @EnumSource(CompletionFailurePolicy.class)
    void When_ReleaseFails_Expect_ResultKeptAndOnFailedReleaseWithoutOnReleased(CompletionFailurePolicy policy)
            throws Exception {
        IdempotencyContext context = context("release-fails", Duration.ofSeconds(5));
        IdempotencyStoreException down = new IdempotencyStoreException("store down");
        doThrow(down).when(store).release(any(), any());

        Outcome<String> outcome = engine(policy).execute(context, () -> "refused", STRING_CODEC, value -> false);

        assertThat(outcome).isEqualTo(new Outcome.Executed<>("refused"));
        assertThat(listener.events).containsExactly("onAcquired", "onFailed:RELEASE");
        assertThat(listener.lastCause).isSameAs(down);
    }

    @Test
    void When_ReleaseLosesTheLease_Expect_OnFailedRelease() throws Exception {
        IdempotencyContext context = context("release-lease-lost", Duration.ofSeconds(5));
        doThrow(new IdempotencyLeaseLostException("lease taken over"))
                .when(store)
                .release(any(), any());

        engine(CompletionFailurePolicy.PROPAGATE).execute(context, () -> "refused", STRING_CODEC, value -> false);

        assertThat(listener.events).containsExactly("onAcquired", "onFailed:RELEASE");
    }

    @Test
    void When_ReleaseThrowsAnError_Expect_OnFailedReleaseAndPropagated() {
        IdempotencyContext context = context("release-error", Duration.ofSeconds(5));
        AssertionError error = new AssertionError("release error");
        doThrow(error).when(store).release(any(), any());

        assertThatThrownBy(() -> engine(CompletionFailurePolicy.PROPAGATE)
                        .execute(context, () -> "refused", STRING_CODEC, value -> false))
                .isSameAs(error);

        assertThat(listener.events).containsExactly("onAcquired", "onFailed:RELEASE");
    }

    @Test
    void When_PolicyDeclines_Expect_HeartbeatStopped() throws Exception {
        AtomicInteger extensions = new AtomicInteger();
        doAnswer(invocation -> extensions.incrementAndGet()).when(store).extendLock(any(), any(), any());

        engine(CompletionFailurePolicy.PROPAGATE)
                .execute(
                        context("declined-heartbeat", Duration.ofMillis(100)),
                        () -> "refused",
                        STRING_CODEC,
                        v -> false);
        int afterRelease = extensions.get();
        Thread.sleep(300);

        assertThat(extensions.get()).as("heartbeats after the release").isEqualTo(afterRelease);
    }

    @Test
    void When_ActionThrows_Expect_PolicyNotAsked() {
        AtomicInteger asked = new AtomicInteger();

        assertThatThrownBy(() -> engine(CompletionFailurePolicy.PROPAGATE)
                        .execute(
                                context("action-throws", Duration.ofSeconds(5)),
                                () -> {
                                    throw new IllegalStateException("handler failed");
                                },
                                STRING_CODEC,
                                value -> asked.incrementAndGet() > 0))
                .isInstanceOf(IllegalStateException.class);

        assertThat(asked).hasValue(0);
        assertThat(listener.events).containsExactly("onAcquired", "onFailed:ACTION");
    }

    @Test
    void When_Duplicate_Expect_PolicyNotAsked() throws Exception {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.duplicate(STRING_CODEC.encode("stored"), Instant.EPOCH));
        AtomicInteger asked = new AtomicInteger();

        Outcome<String> outcome = engine(CompletionFailurePolicy.PROPAGATE)
                .execute(context("duplicate", Duration.ofSeconds(5)), () -> "fresh", STRING_CODEC, value -> {
                    asked.incrementAndGet();
                    return false;
                });

        assertThat(outcome).isEqualTo(new Outcome.Replayed<>("stored", Instant.EPOCH));
        assertThat(asked).hasValue(0);
        verify(store, never()).release(any(), any());
    }

    @Test
    void When_InFlight_Expect_PolicyNotAsked() throws Exception {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.inFlight(Duration.ofSeconds(3)));
        AtomicInteger asked = new AtomicInteger();

        engine(CompletionFailurePolicy.PROPAGATE)
                .execute(context("in-flight", Duration.ofSeconds(5)), () -> "fresh", STRING_CODEC, value -> {
                    asked.incrementAndGet();
                    return false;
                });

        assertThat(asked).hasValue(0);
        verify(store, never()).release(any(), any());
    }

    @Test
    void When_ManyExecutions_Expect_ExactlyOneTerminalPerLease() throws Exception {
        IdempotencyEngine engine = engine(CompletionFailurePolicy.LOG_AND_RETURN);
        doThrow(new IdempotencyStoreException("down")).doNothing().when(store).release(any(), any());

        engine.execute(context("k1", Duration.ofSeconds(5)), () -> "a", STRING_CODEC, value -> false);
        engine.execute(context("k2", Duration.ofSeconds(5)), () -> "b", STRING_CODEC, value -> false);
        engine.execute(context("k3", Duration.ofSeconds(5)), () -> "c", STRING_CODEC, value -> true);
        engine.execute(context("k4", Duration.ofSeconds(5)), () -> "d", STRING_CODEC, value -> {
            throw new IllegalStateException("policy broke");
        });

        assertThat(listener.events.stream().filter("onAcquired"::equals)).hasSize(4);
        assertThat(listener.events.stream().filter(event -> !event.equals("onAcquired")))
                .containsExactly("onFailed:RELEASE", "onReleased", "onCompleted", "onFailed:COMPLETION");
    }

    @Test
    void When_NullPolicy_Expect_Rejected() {
        assertThatThrownBy(() -> engine(CompletionFailurePolicy.PROPAGATE)
                        .execute(context("null-policy", Duration.ofSeconds(5)), () -> "x", STRING_CODEC, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("policy");
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
        public void onReleased(IdempotencyContext ctx, String leaseId) {
            events.add("onReleased");
        }

        @Override
        public void onFailed(IdempotencyContext ctx, String leaseId, Throwable cause, FailurePhase phase) {
            events.add("onFailed:" + phase);
            lastCause = cause;
        }
    }
}
