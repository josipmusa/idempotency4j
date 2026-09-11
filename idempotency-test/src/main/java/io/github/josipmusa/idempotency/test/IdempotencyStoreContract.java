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
package io.github.josipmusa.idempotency.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyIdentity;
import io.github.josipmusa.idempotency.core.IdempotencyPayload;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.NoPayload;
import io.github.josipmusa.idempotency.core.StoredResponse;
import io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// Test methods follow the project's When_Context_Expect_Result convention. This class is a
// JUnit base class that ships as a published artifact, so it lives in main sources and static
// analysis applies its production naming rule to it.
@SuppressWarnings("java:S100")
public abstract class IdempotencyStoreContract {

    // 64-character fingerprint constants (SHA-256 hex length) used across test fixtures
    protected static final String FINGERPRINT_DEFAULT = "0".repeat(64);
    protected static final String FINGERPRINT_A = "a".repeat(64);
    protected static final String FINGERPRINT_B = "b".repeat(64);

    /** The scope every fixture uses unless a test is specifically about scope isolation. */
    protected static final String SCOPE_DEFAULT = "ContractScope.action";

    protected static final String SCOPE_OTHER = "OtherScope.action";

    protected abstract IdempotencyStore store();

    private final ThreadLocal<Map<IdempotencyIdentity, String>> activeLeases =
            ThreadLocal.withInitial(java.util.HashMap::new);

    @AfterEach
    void clearActiveLeases() {
        activeLeases.remove();
    }

    /**
     * Lets real time pass.
     *
     * <p>A store contract has two reasons to need this and no condition it could await on
     * instead. One is a deadline the store owns - a row's {@code expires_at}, a Redis TTL, a
     * lock expiry - which only wall-clock time moves past. The other is holding a lock long
     * enough for another thread to reach the call it is supposed to block in.
     */
    @SuppressWarnings("java:S2925") // letting real time pass is the whole point of this helper
    protected static void sleepFor(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    protected AcquireResult acquire(IdempotencyStore store, IdempotencyContext context) {
        AcquireResult result = store.tryAcquire(context);
        if (result instanceof AcquireResult.Acquired(String leaseId)) {
            activeLeases.get().put(context.identity(), leaseId);
        }
        return result;
    }

    protected void complete(IdempotencyStore store, String key, IdempotencyPayload payload, Duration ttl) {
        complete(store, identity(key), payload, ttl);
    }

    protected void complete(
            IdempotencyStore store, IdempotencyIdentity identity, IdempotencyPayload payload, Duration ttl) {
        store.complete(identity, activeLease(identity), payload, ttl);
    }

    protected void release(IdempotencyStore store, String key) {
        release(store, identity(key));
    }

    protected void release(IdempotencyStore store, IdempotencyIdentity identity) {
        store.release(identity, activeLease(identity));
    }

    protected void extendLock(IdempotencyStore store, String key, Duration extension) {
        store.extendLock(identity(key), activeLease(identity(key)), extension);
    }

    private String activeLease(IdempotencyIdentity identity) {
        return activeLeases.get().getOrDefault(identity, "unknown-test-lease");
    }

    protected static IdempotencyIdentity identity(String key) {
        return new IdempotencyIdentity(SCOPE_DEFAULT, key);
    }

    protected IdempotencyContext contextFor(String key) {
        return new IdempotencyContext(
                SCOPE_DEFAULT, key, Duration.ofHours(1), Duration.ofSeconds(5), FINGERPRINT_DEFAULT);
    }

    protected IdempotencyContext contextFor(String scope, String key, Duration lockTimeout) {
        return new IdempotencyContext(scope, key, Duration.ofHours(1), lockTimeout, FINGERPRINT_DEFAULT);
    }

    protected IdempotencyContext contextFor(String key, Duration lockTimeout) {
        return contextFor(SCOPE_DEFAULT, key, lockTimeout);
    }

    private IdempotencyContext contextFor(String key, Duration ttl, Duration lockTimeout) {
        return new IdempotencyContext(SCOPE_DEFAULT, key, ttl, lockTimeout, FINGERPRINT_DEFAULT);
    }

    protected IdempotencyContext contextFor(String key, String fingerprint) {
        return new IdempotencyContext(SCOPE_DEFAULT, key, Duration.ofHours(1), Duration.ofSeconds(5), fingerprint);
    }

    protected IdempotencyContext contextWithoutFingerprint(String key) {
        return IdempotencyContext.withoutFingerprint(SCOPE_DEFAULT, key, Duration.ofHours(1), Duration.ofSeconds(5));
    }

    private StoredResponse sampleResponse() {
        return new StoredResponse(200, Map.of("X-Request-Id", List.of("abc-123")), "hello".getBytes(), Instant.now());
    }

    @Test
    void When_NewKey_Expect_ReturnsAcquired() {
        AcquireResult result = acquire(store(), contextFor("new-key"));

        assertThat(result).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_CompletedKey_Expect_ReturnsDuplicateWithCorrectResponse() {
        IdempotencyStore s = store();
        String key = "complete-key";
        StoredResponse response = sampleResponse();

        acquire(s, contextFor(key));
        complete(s, key, response, Duration.ofHours(1));

        AcquireResult result = acquire(s, contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
        StoredResponse replayed = storedResponseOf(result);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(replayed.headers()).containsEntry("X-Request-Id", List.of("abc-123"));
        assertThat(replayed.body()).isEqualTo("hello".getBytes());
    }

    @Test
    void When_ReleasedKey_Expect_CanBeAcquiredAgain() {
        IdempotencyStore s = store();
        String key = "release-key";

        var first = (AcquireResult.Acquired) acquire(s, contextFor(key));
        s.release(identity(key), first.leaseId());

        var result = (AcquireResult.Acquired) acquire(s, contextFor(key));

        assertThat(result.leaseId()).isNotEqualTo(first.leaseId());
    }

    @Test
    void When_ExpiredKey_Expect_TreatedAsNew() throws InterruptedException {
        IdempotencyStore s = store();
        String key = "expired-key";

        acquire(s, contextFor(key));
        complete(s, key, sampleResponse(), Duration.ofMillis(1));

        sleepFor(Duration.ofMillis(10));

        AcquireResult result = acquire(s, contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_StaleLock_Expect_IsStolen() throws InterruptedException {
        IdempotencyStore s = store();
        String key = "stale-key";

        acquire(s, contextFor(key, Duration.ofMillis(100)));
        // Simulate crashed caller — do not complete or release

        sleepFor(Duration.ofMillis(150));

        AcquireResult result = acquire(s, contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_StaleLockAndSameLockTimeout_Expect_IsStolen() throws InterruptedException {
        IdempotencyStore s = store();
        String key = "stale-same-timeout";
        Duration sharedTimeout = Duration.ofMillis(100);

        // Acquire with a 100ms lock
        acquire(s, contextFor(key, sharedTimeout));
        // Simulate crashed caller — never complete or release
        sleepFor(Duration.ofMillis(150)); // past lockExpiresAt

        // Second caller uses the SAME timeout — must still steal the stale lock
        AcquireResult result = acquire(s, contextFor(key, sharedTimeout));

        assertThat(result)
                .as("A stale lock should be stealable regardless of the caller's lockTimeout")
                .isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_OldOwnerResumesAfterLockIsStolen_Expect_CannotMutateNewLease() throws InterruptedException {
        IdempotencyStore s = store();
        String key = "stale-owner-fencing";
        var firstContext =
                new IdempotencyContext(SCOPE_DEFAULT, key, Duration.ofHours(1), Duration.ofMillis(30), FINGERPRINT_A);
        var first = (AcquireResult.Acquired) s.tryAcquire(firstContext);

        sleepFor(Duration.ofMillis(80));

        var secondContext =
                new IdempotencyContext(SCOPE_DEFAULT, key, Duration.ofHours(1), Duration.ofSeconds(5), FINGERPRINT_B);
        var second = (AcquireResult.Acquired) s.tryAcquire(secondContext);
        StoredResponse staleResponse = new StoredResponse(200, Map.of(), "stale".getBytes(), Instant.now());

        assertThatThrownBy(() -> s.complete(identity(key), first.leaseId(), staleResponse, Duration.ofHours(1)))
                .isInstanceOf(IdempotencyLeaseLostException.class)
                .hasMessageContaining("lease");
        assertThatThrownBy(() -> s.release(identity(key), first.leaseId()))
                .isInstanceOf(IdempotencyLeaseLostException.class)
                .hasMessageContaining("lease");
        assertThatCode(() -> s.extendLock(identity(key), first.leaseId(), Duration.ofHours(1)))
                .doesNotThrowAnyException();

        StoredResponse currentResponse = new StoredResponse(201, Map.of(), "current".getBytes(), Instant.now());
        s.complete(identity(key), second.leaseId(), currentResponse, Duration.ofHours(1));

        AcquireResult replay = s.tryAcquire(secondContext);
        assertThat(replay).isInstanceOf(AcquireResult.Duplicate.class);
        assertThat(storedResponseOf(replay).body()).isEqualTo("current".getBytes());
    }

    @Test
    void When_InFlightKey_Expect_BlocksAndReturnsDuplicateAfterCompletion()
            throws InterruptedException, ExecutionException, TimeoutException {
        IdempotencyStore s = store();
        String key = "inflight-key";
        StoredResponse response = sampleResponse();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            long[] thread1CompleteTime = new long[1];
            long[] thread2ResultTime = new long[1];
            CountDownLatch lockAcquired = new CountDownLatch(1);

            Future<?> thread1 = executor.submit(() -> {
                acquire(s, contextFor(key));
                lockAcquired.countDown();
                // Hold the lock long enough for thread 2 to reach tryAcquire and block in it
                sleepFor(Duration.ofMillis(300));
                complete(s, key, response, Duration.ofHours(1));
                thread1CompleteTime[0] = System.nanoTime();
                return null;
            });

            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            Future<AcquireResult> thread2 = executor.submit(() -> {
                AcquireResult result = acquire(s, contextFor(key));
                thread2ResultTime[0] = System.nanoTime();
                return result;
            });

            thread1.get(5, TimeUnit.SECONDS);
            AcquireResult result = thread2.get(5, TimeUnit.SECONDS);

            assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
            AcquireResult.Duplicate duplicate = (AcquireResult.Duplicate) result;
            assertThat(storedResponseOf(duplicate).statusCode()).isEqualTo(200);
            assertThat(storedResponseOf(duplicate).headers()).containsEntry("X-Request-Id", List.of("abc-123"));
            assertThat(storedResponseOf(duplicate).body()).isEqualTo("hello".getBytes());
            assertThat(thread2ResultTime[0]).isGreaterThan(thread1CompleteTime[0]);
        }
    }

    @Test
    void When_InFlightKeyLockTimeoutExceeded_Expect_ReturnsLockTimeout()
            throws InterruptedException, ExecutionException, TimeoutException {
        IdempotencyStore s = store();
        String key = "timeout-key";

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                // Thread 1 acquires and never completes
                Future<?> thread1 = executor.submit(() -> {
                    acquire(s, contextFor(key, Duration.ofSeconds(30)));
                    lockAcquired.countDown();
                    // Never complete or release — simulate a hang that outlasts the test
                    return releaseHolder.await(30, TimeUnit.SECONDS);
                });

                assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

                long start = System.nanoTime();
                Future<AcquireResult> thread2 =
                        executor.submit(() -> acquire(s, contextFor(key, Duration.ofMillis(200))));

                AcquireResult result = thread2.get(5, TimeUnit.SECONDS);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                assertThat(result).isInstanceOf(AcquireResult.LockTimeout.class);
                assertThat(elapsed).isBetween(200L, 600L);
                thread1.cancel(true);
            } finally {
                releaseHolder.countDown();
            }
        }
    }

    @Test
    void When_ConcurrentRequests_Expect_OnlyOneAcquires()
            throws InterruptedException, ExecutionException, TimeoutException {
        IdempotencyStore s = store();
        String key = "concurrent-key";
        StoredResponse response = sampleResponse();
        int threadCount = 20;

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<AcquireResult> results = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    AcquireResult result = acquire(s, contextFor(key));
                    if (result instanceof AcquireResult.Acquired) {
                        complete(s, key, response, Duration.ofHours(1));
                    }
                    results.add(result);
                }));
            }

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            long acquiredCount = results.stream()
                    .filter(r -> r instanceof AcquireResult.Acquired)
                    .count();
            long duplicateCount = results.stream()
                    .filter(r -> r instanceof AcquireResult.Duplicate)
                    .count();

            assertThat(acquiredCount).isEqualTo(1);
            assertThat(duplicateCount).isEqualTo((long) threadCount - 1);

            results.stream()
                    .filter(r -> r instanceof AcquireResult.Duplicate)
                    .map(IdempotencyStoreContract::storedResponseOf)
                    .forEach(r -> {
                        assertThat(r.statusCode()).isEqualTo(200);
                        assertThat(r.body()).isEqualTo("hello".getBytes());
                    });
        }
    }

    @Test
    void When_DifferentKeys_Expect_AcquireIndependently()
            throws InterruptedException, ExecutionException, TimeoutException {
        IdempotencyStore s = store();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AcquireResult> futureA = executor.submit(() -> acquire(s, contextFor("key-a")));
            Future<AcquireResult> futureB = executor.submit(() -> acquire(s, contextFor("key-b")));

            AcquireResult resultA = futureA.get(5, TimeUnit.SECONDS);
            AcquireResult resultB = futureB.get(5, TimeUnit.SECONDS);

            assertThat(resultA).isInstanceOf(AcquireResult.Acquired.class);
            assertThat(resultB).isInstanceOf(AcquireResult.Acquired.class);
        }
    }

    @Test
    void When_ReleaseAllowsRetry_Expect_ActionCanSucceedOnSecondAttempt() {
        IdempotencyStore s = store();
        String key = "retry-key";
        StoredResponse response = sampleResponse();

        // First attempt — acquire and fail
        AcquireResult first = acquire(s, contextFor(key));
        assertThat(first).isInstanceOf(AcquireResult.Acquired.class);
        release(s, key);

        // Second attempt — acquire and succeed
        AcquireResult second = acquire(s, contextFor(key));
        assertThat(second).isInstanceOf(AcquireResult.Acquired.class);
        complete(s, key, response, Duration.ofHours(1));

        // Third attempt — should be duplicate
        AcquireResult third = acquire(s, contextFor(key));
        assertThat(third).isInstanceOf(AcquireResult.Duplicate.class);
        AcquireResult.Duplicate duplicate = (AcquireResult.Duplicate) third;
        assertThat(storedResponseOf(duplicate).statusCode()).isEqualTo(200);
        assertThat(storedResponseOf(duplicate).body()).isEqualTo("hello".getBytes());
    }

    // --- extendLock contract ---

    @Test
    void When_ExtendLockInProgressKey_Expect_LockNotStolen()
            throws InterruptedException, ExecutionException, TimeoutException {
        IdempotencyStore s = store();
        String key = "extend-key";

        // Acquire with short lock (100ms)
        acquire(s, contextFor(key, Duration.ofMillis(100)));

        // Extend lock to 500ms from now
        extendLock(s, key, Duration.ofMillis(500));

        // Wait past the original 100ms lock expiry
        sleepFor(Duration.ofMillis(150));

        // Lock should still be valid — second caller should NOT steal it
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<AcquireResult> future = executor.submit(() -> acquire(s, contextFor(key, Duration.ofMillis(100))));
            AcquireResult result = future.get(5, TimeUnit.SECONDS);

            assertThat(result)
                    .as("Extended lock should not be stealable before new expiry")
                    .isInstanceOf(AcquireResult.LockTimeout.class);
        }
    }

    @Test
    void When_ExtendLockUnknownKey_Expect_SilentlyIgnored() {
        IdempotencyStore s = store();

        // Must not throw — heartbeat may fire after key is already gone
        extendLock(s, "nonexistent-key", Duration.ofSeconds(10));
    }

    @Test
    void When_ExtendLockCompletedKey_Expect_SilentlyIgnored() {
        IdempotencyStore s = store();
        String key = "completed-extend-key";
        StoredResponse response = sampleResponse();

        acquire(s, contextFor(key));
        complete(s, key, response, Duration.ofHours(1));

        // Must not throw — heartbeat may fire after completion
        extendLock(s, key, Duration.ofSeconds(10));

        // Key should still be a valid duplicate
        AcquireResult result = acquire(s, contextFor(key));
        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
    }

    // --- TTL durability ---

    @Test
    void When_CompletedKeyWithinTtl_Expect_ReturnsDuplicate() {
        IdempotencyStore s = store();
        String key = "ttl-durable-key";
        StoredResponse response = sampleResponse();

        acquire(s, contextFor(key));
        complete(s, key, response, Duration.ofHours(1));

        // Immediately re-acquire — must still be Duplicate
        AcquireResult result = acquire(s, contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
        AcquireResult.Duplicate duplicate = (AcquireResult.Duplicate) result;
        assertThat(storedResponseOf(duplicate).statusCode()).isEqualTo(200);
    }

    // --- Error contracts ---

    @Test
    void When_CompleteOnNonExistentKey_Expect_ThrowsStoreException() {
        IdempotencyStore s = store();

        assertThatThrownBy(() -> complete(s, "ghost-key", sampleResponse(), Duration.ofHours(1)))
                .isInstanceOf(IdempotencyLeaseLostException.class);
    }

    @Test
    void When_CompleteOnReleasedKey_Expect_ThrowsStoreException() {
        IdempotencyStore s = store();
        String key = "failed-key";

        acquire(s, contextFor(key));
        release(s, key);

        assertThatThrownBy(() -> complete(s, key, sampleResponse(), Duration.ofHours(1)))
                .isInstanceOf(IdempotencyLeaseLostException.class);
    }

    @Test
    void When_ReleaseOnNonExistentKey_Expect_ThrowsStoreException() {
        IdempotencyStore s = store();

        assertThatThrownBy(() -> release(s, "ghost-key")).isInstanceOf(IdempotencyLeaseLostException.class);
    }

    @Test
    void When_CompleteOnCompletedKey_Expect_ThrowsStoreException() {
        IdempotencyStore s = store();
        String key = "double-complete-key";

        acquire(s, contextFor(key));
        complete(s, key, sampleResponse(), Duration.ofHours(1));

        assertThatThrownBy(() -> complete(s, key, sampleResponse(), Duration.ofHours(1)))
                .isInstanceOf(IdempotencyLeaseLostException.class);
    }

    @Test
    void When_ReleaseOnCompletedKey_Expect_ThrowsStoreException() {
        IdempotencyStore s = store();
        String key = "release-completed-key";

        acquire(s, contextFor(key));
        complete(s, key, sampleResponse(), Duration.ofHours(1));

        assertThatThrownBy(() -> release(s, key)).isInstanceOf(IdempotencyLeaseLostException.class);
    }

    // --- Full lifecycle ---

    @Test
    void When_FullLifecycle_Expect_AcquireCompleteTtlExpiresReacquireCompletes() throws InterruptedException {
        IdempotencyStore s = store();
        String key = "lifecycle-key";

        // First generation: acquire → complete
        AcquireResult first = acquire(s, contextFor(key));
        assertThat(first).isInstanceOf(AcquireResult.Acquired.class);

        StoredResponse firstResponse = new StoredResponse(200, Map.of(), "first".getBytes(), Instant.now());
        complete(s, key, firstResponse, Duration.ofMillis(1));

        // Wait for TTL to expire
        sleepFor(Duration.ofMillis(10));

        // Second generation: re-acquire → complete with different response
        AcquireResult second = acquire(s, contextFor(key));
        assertThat(second)
                .as("Key should be acquirable again after TTL expires")
                .isInstanceOf(AcquireResult.Acquired.class);

        StoredResponse secondResponse = new StoredResponse(201, Map.of(), "second".getBytes(), Instant.now());
        complete(s, key, secondResponse, Duration.ofHours(1));

        // Verify the new response is stored, not the old one
        AcquireResult third = acquire(s, contextFor(key));
        assertThat(third).isInstanceOf(AcquireResult.Duplicate.class);
        AcquireResult.Duplicate duplicate = (AcquireResult.Duplicate) third;
        assertThat(storedResponseOf(duplicate).statusCode()).isEqualTo(201);
        assertThat(storedResponseOf(duplicate).body()).isEqualTo("second".getBytes());
    }

    // --- Additional edge-case contracts ---

    @Test
    void When_ExtendLockFailedKey_Expect_SilentlyIgnored() {
        IdempotencyStore s = store();
        String key = "failed-extend-key";

        acquire(s, contextFor(key));
        release(s, key);

        // Must not throw — FAILED is not IN_PROGRESS
        extendLock(s, key, Duration.ofSeconds(10));
    }

    @Test
    void When_ReleaseOnReleasedKey_Expect_ThrowsStoreException() {
        IdempotencyStore s = store();
        String key = "double-release-key";

        acquire(s, contextFor(key));
        release(s, key);

        assertThatThrownBy(() -> release(s, key)).isInstanceOf(IdempotencyLeaseLostException.class);
    }

    @Test
    void When_FailedKeyUnderContention_Expect_TimeoutRespected()
            throws InterruptedException, ExecutionException, TimeoutException {
        IdempotencyStore s = store();
        String key = "contended-failed-key";
        int chaosThreadCount = 20;
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch chaosReady = new CountDownLatch(chaosThreadCount);

        // Put key in FAILED state
        acquire(s, contextFor(key, Duration.ofSeconds(10)));
        release(s, key);

        try (ExecutorService executor = Executors.newFixedThreadPool(chaosThreadCount + 1)) {
            // The inner finally is load-bearing: close() awaits termination, and the chaos
            // loops below only exit once stop is set. Setting it in an outer finally would
            // run after close() had already started waiting, and the test would hang.
            try {
                // Chaos threads continuously steal and release the key, creating hot contention
                for (int i = 0; i < chaosThreadCount; i++) {
                    executor.submit(() -> {
                        chaosReady.countDown();
                        while (!stop.get()) {
                            AcquireResult r = acquire(s, contextFor(key, Duration.ofSeconds(10)));
                            if (r instanceof AcquireResult.Acquired) {
                                release(s, key);
                            }
                        }
                    });
                }
                assertThat(chaosReady.await(5, TimeUnit.SECONDS)).isTrue();

                // Victim thread with short lockTimeout — must return within bounded time
                Future<AcquireResult> victim =
                        executor.submit(() -> acquire(s, contextFor(key, Duration.ofMillis(200))));

                // Without the fix, the victim can loop indefinitely under contention.
                // victim.get(5s) will throw TimeoutException, failing the test.
                AcquireResult result = victim.get(5, TimeUnit.SECONDS);

                assertThat(result).isInstanceOfAny(AcquireResult.Acquired.class, AcquireResult.LockTimeout.class);
            } finally {
                stop.set(true);
            }
        }
    }

    @Test
    void When_AcquiredKey_Expect_SecondAcquireTimesOut() {
        IdempotencyStore s = store();
        String key = "self-deadlock-key";

        acquire(s, contextFor(key, Duration.ofSeconds(30)));

        // Second acquire on same key should timeout, not succeed
        AcquireResult result = acquire(s, contextFor(key, Duration.ofMillis(200)));

        assertThat(result).isInstanceOf(AcquireResult.LockTimeout.class);
    }

    // --- purgeExpired contract ---

    @Test
    void When_ExpiredTtlEntryExists_Expect_PurgeRemovesIt() throws InterruptedException {
        IdempotencyStore s = store();
        // Both TTL and lockTimeout are short so the IN_PROGRESS entry is eligible for purge
        var ctx = contextFor("purge-expired-1", Duration.ofMillis(10), Duration.ofMillis(2));
        acquire(s, ctx);
        sleepFor(Duration.ofMillis(50));
        assertThat(s.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(acquire(s, ctx)).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_NonExpiredTtlEntryExists_Expect_PurgeKeepsIt() {
        IdempotencyStore s = store();
        var ctx = contextFor("purge-keep-1", Duration.ofMinutes(10), Duration.ofSeconds(5));
        acquire(s, ctx);
        complete(s, ctx.key(), sampleResponse(), ctx.ttl());
        s.purgeExpired();
        var result = acquire(s, ctx);
        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
    }

    @Test
    void When_CompletedEntryTtlExpired_Expect_PurgeRemovesIt() throws InterruptedException {
        IdempotencyStore s = store();
        var ctx = contextFor("purge-completed-1", Duration.ofMinutes(10), Duration.ofSeconds(5));
        acquire(s, ctx);
        complete(
                s,
                ctx.key(),
                new StoredResponse(200, Map.of(), "body".getBytes(), Instant.now()),
                Duration.ofMillis(1));
        sleepFor(Duration.ofMillis(50));
        assertThat(s.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(acquire(s, ctx)).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_StaleInProgressLockExpiredTtlNotExpired_Expect_PurgeKeepsIt() throws InterruptedException {
        IdempotencyStore s = store();
        // Lock expires quickly (2ms), but TTL is long (10 min)
        var ctx = contextFor("purge-stale-inprogress-1", Duration.ofMinutes(10), Duration.ofMillis(2));
        acquire(s, ctx);
        sleepFor(Duration.ofMillis(50));
        int purged = s.purgeExpired();
        // The entry TTL has not expired, so purge should keep it (even though lock expired).
        // A correct purge must NOT remove an IN_PROGRESS entry whose TTL is still valid.
        assertThat(purged)
                .as("Purge must not remove IN_PROGRESS entry whose TTL is still valid")
                .isEqualTo(0);
    }

    @Test
    void When_StaleInProgressBothLockAndTtlExpired_Expect_PurgeRemovesIt() throws InterruptedException {
        IdempotencyStore s = store();
        // Both lockTimeout and TTL are short — entry is fully expired and safe to purge
        var ctx = contextFor("purge-stale-both-1", Duration.ofMillis(2), Duration.ofMillis(2));
        acquire(s, ctx);
        sleepFor(Duration.ofMillis(50));
        assertThat(s.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(acquire(s, ctx)).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_NoExpiredEntries_Expect_PurgeReturnsZero() {
        IdempotencyStore s = store();
        var ctx = contextFor("purge-none-1", Duration.ofMinutes(10), Duration.ofSeconds(5));
        acquire(s, ctx);
        int removed = s.purgeExpired();
        assertThat(removed).isEqualTo(0);
    }

    // ── Scope isolation ─────────────────────────────────────────────────

    @Test
    void When_SameKeyDifferentScope_Expect_BothAcquired() {
        IdempotencyStore s = store();
        String key = "shared-key-two-scopes";

        AcquireResult first = acquire(s, contextFor(SCOPE_DEFAULT, key, Duration.ofSeconds(5)));
        AcquireResult second = acquire(s, contextFor(SCOPE_OTHER, key, Duration.ofMillis(50)));

        assertThat(first).isInstanceOf(AcquireResult.Acquired.class);
        assertThat(second)
                .as("The same key under another scope is another unit of work and must not block or dedupe")
                .isInstanceOf(AcquireResult.Acquired.class);
        assertThat(((AcquireResult.Acquired) second).leaseId())
                .isNotEqualTo(((AcquireResult.Acquired) first).leaseId());
    }

    @Test
    void When_CompleteInOneScope_Expect_OtherScopeStillAcquires() {
        IdempotencyStore s = store();
        String key = "completed-in-one-scope";

        acquire(s, contextFor(SCOPE_DEFAULT, key, Duration.ofSeconds(5)));
        complete(s, new IdempotencyIdentity(SCOPE_DEFAULT, key), sampleResponse(), Duration.ofHours(1));

        AcquireResult sameScope = acquire(s, contextFor(SCOPE_DEFAULT, key, Duration.ofSeconds(5)));
        AcquireResult otherScope = acquire(s, contextFor(SCOPE_OTHER, key, Duration.ofMillis(50)));

        assertThat(sameScope).isInstanceOf(AcquireResult.Duplicate.class);
        assertThat(otherScope)
                .as("A completion is visible only within its own scope")
                .isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_ReleaseInOneScope_Expect_OtherScopeUntouched() {
        IdempotencyStore s = store();
        String key = "released-in-one-scope";
        IdempotencyIdentity releasedIdentity = new IdempotencyIdentity(SCOPE_DEFAULT, key);
        IdempotencyIdentity heldIdentity = new IdempotencyIdentity(SCOPE_OTHER, key);

        var released = (AcquireResult.Acquired) acquire(s, contextFor(SCOPE_DEFAULT, key, Duration.ofSeconds(5)));
        var held = (AcquireResult.Acquired) acquire(s, contextFor(SCOPE_OTHER, key, Duration.ofSeconds(5)));

        release(s, releasedIdentity);

        // The held lease in the other scope is still the owner: the release must not have
        // touched it, so its own release succeeds and a stale release with the wrong lease fails.
        assertThatThrownBy(() -> s.release(heldIdentity, released.leaseId()))
                .isInstanceOf(IdempotencyLeaseLostException.class);
        assertThatCode(() -> s.release(heldIdentity, held.leaseId())).doesNotThrowAnyException();
        assertThat(acquire(s, contextFor(SCOPE_DEFAULT, key, Duration.ofSeconds(5))))
                .isInstanceOf(AcquireResult.Acquired.class);
    }

    // ── Fingerprint tests ──────────────────────────────────────────────

    @Test
    void When_SameKeyAndSameFingerprint_Expect_Duplicate() {
        IdempotencyStore s = store();
        var context = contextFor("fp-same", FINGERPRINT_A);
        acquire(s, context);
        complete(s, "fp-same", sampleResponse(), Duration.ofHours(1));

        var result = acquire(s, context);

        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
    }

    @Test
    void When_SameKeyButDifferentFingerprint_Expect_FingerprintMismatch() {
        IdempotencyStore s = store();
        var original = contextFor("fp-diff", FINGERPRINT_A);
        acquire(s, original);
        complete(s, "fp-diff", sampleResponse(), Duration.ofHours(1));

        var reused = contextFor("fp-diff", FINGERPRINT_B);
        var result = acquire(s, reused);

        assertThat(result).isInstanceOf(AcquireResult.FingerprintMismatch.class);
        var mismatch = (AcquireResult.FingerprintMismatch) result;
        assertThat(mismatch.storedFingerprint()).isEqualTo(FINGERPRINT_A);
        assertThat(mismatch.receivedFingerprint()).isEqualTo(FINGERPRINT_B);
    }

    @Test
    void When_KeyReleasedAndReAcquiredWithDifferentFingerprint_Expect_Acquired() {
        IdempotencyStore s = store();
        var first = contextFor("fp-reacquire", FINGERPRINT_A);
        acquire(s, first);
        release(s, "fp-reacquire");

        var second = contextFor("fp-reacquire", FINGERPRINT_B);
        var result = acquire(s, second);

        assertThat(result).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_StolenFailedKeyCompletedWithNewFingerprint_Expect_OldFingerprintIsMismatch() {
        IdempotencyStore s = store();
        String key = "fp-stolen-complete";

        // First attempt with FINGERPRINT_A — fails
        acquire(s, contextFor(key, FINGERPRINT_A));
        release(s, key);

        // Second attempt steals with FINGERPRINT_B and completes
        acquire(s, contextFor(key, FINGERPRINT_B));
        complete(s, key, sampleResponse(), Duration.ofHours(1));

        // Third request with original FINGERPRINT_A must be rejected as a mismatch —
        // the stored fingerprint is now B, not A
        var result = acquire(s, contextFor(key, FINGERPRINT_A));

        assertThat(result)
                .as("Original fingerprint must be a mismatch after the key was re-completed with a new fingerprint")
                .isInstanceOf(AcquireResult.FingerprintMismatch.class);
        var mismatch = (AcquireResult.FingerprintMismatch) result;
        assertThat(mismatch.storedFingerprint()).isEqualTo(FINGERPRINT_B);
        assertThat(mismatch.receivedFingerprint()).isEqualTo(FINGERPRINT_A);
    }

    @Test
    void When_KeyReleasedAfterLockExpired_Expect_FailedRecordNotImmediatelyPurgeable() throws InterruptedException {
        IdempotencyStore s = store();
        String key = "failed-expiry-contract";

        // release() dates the FAILED record at now + lockTimeout, so lockTimeout is also the
        // budget this test has between release() and purgeExpired(). Keep it far above the
        // round-trip cost of those two calls: a 50ms budget was roughly the cost of the calls
        // themselves against a containerised database and made this test flaky on CI.
        Duration lockTimeout = Duration.ofSeconds(2);

        acquire(s, contextFor(key, lockTimeout));

        // Wait for the lock to expire, then release
        sleepFor(lockTimeout.plusMillis(100));
        release(s, key);

        // Purge immediately — the FAILED record should NOT be eligible yet.
        int purged = s.purgeExpired();

        assertThat(purged)
                .as("FAILED record should survive an immediate purgeExpired() call; "
                        + "its expires_at must be now + lockTimeout, not the already-past lock_expires_at")
                .isEqualTo(0);
    }

    // ── Optional fingerprint tests ─────────────────────────────────────

    @Test
    void When_NeitherStoredNorIncomingHasFingerprint_Expect_Duplicate() {
        IdempotencyStore s = store();
        String key = "fp-absent-absent";
        acquire(s, contextWithoutFingerprint(key));
        complete(s, key, sampleResponse(), Duration.ofHours(1));

        var result = acquire(s, contextWithoutFingerprint(key));

        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
    }

    @Test
    void When_StoredHasNoFingerprintButIncomingDoes_Expect_DuplicateNotMismatch() {
        IdempotencyStore s = store();
        String key = "fp-absent-present";
        acquire(s, contextWithoutFingerprint(key));
        complete(s, key, sampleResponse(), Duration.ofHours(1));

        var result = acquire(s, contextFor(key, FINGERPRINT_A));

        assertThat(result)
                .as("A caller that fingerprints cannot mismatch a record stored without one")
                .isInstanceOf(AcquireResult.Duplicate.class);
    }

    @Test
    void When_StoredHasFingerprintButIncomingDoesNot_Expect_DuplicateNotMismatch() {
        IdempotencyStore s = store();
        String key = "fp-present-absent";
        acquire(s, contextFor(key, FINGERPRINT_A));
        complete(s, key, sampleResponse(), Duration.ofHours(1));

        var result = acquire(s, contextWithoutFingerprint(key));

        assertThat(result)
                .as("A caller that does not fingerprint cannot contradict a record stored with one")
                .isInstanceOf(AcquireResult.Duplicate.class);
    }

    @Test
    void When_BothHaveDifferentFingerprints_Expect_FingerprintMismatch() {
        IdempotencyStore s = store();
        String key = "fp-present-different";
        acquire(s, contextFor(key, FINGERPRINT_A));
        complete(s, key, sampleResponse(), Duration.ofHours(1));

        var result = acquire(s, contextFor(key, FINGERPRINT_B));

        assertThat(result).isInstanceOf(AcquireResult.FingerprintMismatch.class);
        var mismatch = (AcquireResult.FingerprintMismatch) result;
        assertThat(mismatch.storedFingerprint()).isEqualTo(FINGERPRINT_A);
        assertThat(mismatch.receivedFingerprint()).isEqualTo(FINGERPRINT_B);
    }

    @Test
    void When_KeyAcquiredWithoutFingerprint_Expect_InFlightCallerStillBlocked() {
        IdempotencyStore s = store();
        String key = "fp-absent-in-flight";
        acquire(s, contextWithoutFingerprint(key));

        var result = s.tryAcquire(
                IdempotencyContext.withoutFingerprint(SCOPE_DEFAULT, key, Duration.ofHours(1), Duration.ofMillis(50)));

        assertThat(result).isInstanceOf(AcquireResult.LockTimeout.class);
    }

    // ── Payload tests ──────────────────────────────────────────────────

    @Test
    void When_CompletedWithNoPayload_Expect_DuplicateReturnsNoPayloadWithStoredCompletedAt() {
        IdempotencyStore s = store();
        String key = "payload-none";
        Instant completedAt = Instant.now().minusSeconds(7);

        acquire(s, contextFor(key));
        complete(s, key, NoPayload.at(completedAt), Duration.ofHours(1));

        var result = acquire(s, contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
        var payload = ((AcquireResult.Duplicate) result).payload();
        assertThat(payload).isInstanceOf(NoPayload.class);
        assertThat(payload.completedAt().toEpochMilli()).isEqualTo(completedAt.toEpochMilli());
    }

    @Test
    void When_CompletedWithNoPayloadAndNoFingerprint_Expect_RoundTrips() {
        IdempotencyStore s = store();
        String key = "payload-none-no-fp";
        Instant completedAt = Instant.now();

        acquire(s, contextWithoutFingerprint(key));
        complete(s, key, NoPayload.at(completedAt), Duration.ofHours(1));

        var result = acquire(s, contextWithoutFingerprint(key));

        assertThat(((AcquireResult.Duplicate) result).payload()).isInstanceOf(NoPayload.class);
    }

    @Test
    void When_NoPayloadRecordIsReleased_Expect_KeyIsReacquirable() {
        IdempotencyStore s = store();
        String key = "payload-none-release";

        acquire(s, contextWithoutFingerprint(key));
        release(s, key);

        assertThat(acquire(s, contextWithoutFingerprint(key))).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_ResponseWithEmptyBodyCompleted_Expect_StillReadBackAsStoredResponse() {
        IdempotencyStore s = store();
        String key = "payload-empty-body";
        var response = new StoredResponse(204, Map.of(), new byte[0], Instant.now());

        acquire(s, contextFor(key));
        complete(s, key, response, Duration.ofHours(1));

        var payload = ((AcquireResult.Duplicate) acquire(s, contextFor(key))).payload();

        assertThat(payload).isInstanceOf(StoredResponse.class);
        assertThat(((StoredResponse) payload).statusCode()).isEqualTo(204);
        assertThat(((StoredResponse) payload).body()).isEmpty();
    }

    protected static StoredResponse storedResponseOf(AcquireResult result) {
        var payload = ((AcquireResult.Duplicate) result).payload();
        assertThat(payload).isInstanceOf(StoredResponse.class);
        return (StoredResponse) payload;
    }
}
