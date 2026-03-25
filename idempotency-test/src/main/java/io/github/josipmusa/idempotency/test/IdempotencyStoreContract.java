package io.github.josipmusa.idempotency.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.josipmusa.core.AcquireResult;
import io.github.josipmusa.core.IdempotencyContext;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.core.StoredResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public abstract class IdempotencyStoreContract {

    protected abstract IdempotencyStore store();

    protected IdempotencyContext contextFor(String key) {
        return new IdempotencyContext(key, Duration.ofHours(1), Duration.ofSeconds(5));
    }

    protected IdempotencyContext contextFor(String key, Duration lockTimeout) {
        return new IdempotencyContext(key, Duration.ofHours(1), lockTimeout);
    }

    private StoredResponse sampleResponse() {
        return new StoredResponse(200, Map.of("X-Request-Id", List.of("abc-123")), "hello".getBytes(), Instant.now());
    }

    @Test
    void newKey_returnsAcquired() {
        AcquireResult result = store().tryAcquire(contextFor("new-key"));

        assertThat(result).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void completedKey_returnsDuplicateWithCorrectResponse() {
        IdempotencyStore s = store();
        String key = "complete-key";
        StoredResponse response = sampleResponse();

        s.tryAcquire(contextFor(key));
        s.complete(key, response, Duration.ofHours(1));

        AcquireResult result = s.tryAcquire(contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
        AcquireResult.Duplicate duplicate = (AcquireResult.Duplicate) result;
        assertThat(duplicate.response().statusCode()).isEqualTo(200);
        assertThat(duplicate.response().headers()).containsEntry("X-Request-Id", List.of("abc-123"));
        assertThat(duplicate.response().body()).isEqualTo("hello".getBytes());
    }

    @Test
    void releasedKey_canBeAcquiredAgain() {
        IdempotencyStore s = store();
        String key = "release-key";

        s.tryAcquire(contextFor(key));
        s.release(key);

        AcquireResult result = s.tryAcquire(contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void expiredKey_treatedAsNew() throws InterruptedException {
        IdempotencyStore s = store();
        String key = "expired-key";

        s.tryAcquire(contextFor(key));
        s.complete(key, sampleResponse(), Duration.ofMillis(1));

        Thread.sleep(10);

        AcquireResult result = s.tryAcquire(contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void staleLock_isStolen() throws InterruptedException {
        IdempotencyStore s = store();
        String key = "stale-key";

        s.tryAcquire(contextFor(key, Duration.ofMillis(100)));
        // Simulate crashed caller — do not complete or release

        Thread.sleep(150);

        AcquireResult result = s.tryAcquire(contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void inFlightKey_blocksAndReturnsDuplicateAfterCompletion() throws Exception {
        IdempotencyStore s = store();
        String key = "inflight-key";
        StoredResponse response = sampleResponse();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            long[] thread1CompleteTime = new long[1];
            long[] thread2ResultTime = new long[1];

            Future<?> thread1 = executor.submit(() -> {
                s.tryAcquire(contextFor(key));
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                s.complete(key, response, Duration.ofHours(1));
                thread1CompleteTime[0] = System.nanoTime();
            });

            Thread.sleep(50);

            Future<AcquireResult> thread2 = executor.submit(() -> {
                AcquireResult result = s.tryAcquire(contextFor(key));
                thread2ResultTime[0] = System.nanoTime();
                return result;
            });

            thread1.get(5, TimeUnit.SECONDS);
            AcquireResult result = thread2.get(5, TimeUnit.SECONDS);

            assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
            AcquireResult.Duplicate duplicate = (AcquireResult.Duplicate) result;
            assertThat(duplicate.response().statusCode()).isEqualTo(200);
            assertThat(duplicate.response().headers()).containsEntry("X-Request-Id", List.of("abc-123"));
            assertThat(duplicate.response().body()).isEqualTo("hello".getBytes());
            assertThat(thread2ResultTime[0]).isGreaterThan(thread1CompleteTime[0]);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void inFlightKey_lockTimeoutExceeded_returnsLockTimeout() throws Exception {
        IdempotencyStore s = store();
        String key = "timeout-key";

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Thread 1 acquires and never completes
            Future<?> thread1 = executor.submit(() -> {
                s.tryAcquire(contextFor(key, Duration.ofSeconds(30)));
                // Never complete or release — simulate infinite hang
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            Thread.sleep(50);

            long start = System.nanoTime();
            Future<AcquireResult> thread2 =
                    executor.submit(() -> s.tryAcquire(contextFor(key, Duration.ofMillis(200))));

            AcquireResult result = thread2.get(5, TimeUnit.SECONDS);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(result).isInstanceOf(AcquireResult.LockTimeout.class);
            assertThat(elapsed).isBetween(200L, 400L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRequests_onlyOneAcquires() throws Exception {
        IdempotencyStore s = store();
        String key = "concurrent-key";
        StoredResponse response = sampleResponse();
        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<AcquireResult> results = Collections.synchronizedList(new ArrayList<>());

        try {
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

                    AcquireResult result = s.tryAcquire(contextFor(key));
                    if (result instanceof AcquireResult.Acquired) {
                        s.complete(key, response, Duration.ofHours(1));
                    }
                    results.add(result);
                }));
            }

            readyLatch.await(5, TimeUnit.SECONDS);
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
            assertThat(duplicateCount).isEqualTo(threadCount - 1);

            results.stream()
                    .filter(r -> r instanceof AcquireResult.Duplicate)
                    .map(r -> ((AcquireResult.Duplicate) r).response())
                    .forEach(r -> {
                        assertThat(r.statusCode()).isEqualTo(200);
                        assertThat(r.body()).isEqualTo("hello".getBytes());
                    });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void differentKeys_acquireIndependently() throws Exception {
        IdempotencyStore s = store();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AcquireResult> futureA = executor.submit(() -> s.tryAcquire(contextFor("key-a")));
            Future<AcquireResult> futureB = executor.submit(() -> s.tryAcquire(contextFor("key-b")));

            AcquireResult resultA = futureA.get(5, TimeUnit.SECONDS);
            AcquireResult resultB = futureB.get(5, TimeUnit.SECONDS);

            assertThat(resultA).isInstanceOf(AcquireResult.Acquired.class);
            assertThat(resultB).isInstanceOf(AcquireResult.Acquired.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void releaseAllowsRetry_actionCanSucceedOnSecondAttempt() {
        IdempotencyStore s = store();
        String key = "retry-key";
        StoredResponse response = sampleResponse();

        // First attempt — acquire and fail
        AcquireResult first = s.tryAcquire(contextFor(key));
        assertThat(first).isInstanceOf(AcquireResult.Acquired.class);
        s.release(key);

        // Second attempt — acquire and succeed
        AcquireResult second = s.tryAcquire(contextFor(key));
        assertThat(second).isInstanceOf(AcquireResult.Acquired.class);
        s.complete(key, response, Duration.ofHours(1));

        // Third attempt — should be duplicate
        AcquireResult third = s.tryAcquire(contextFor(key));
        assertThat(third).isInstanceOf(AcquireResult.Duplicate.class);
        AcquireResult.Duplicate duplicate = (AcquireResult.Duplicate) third;
        assertThat(duplicate.response().statusCode()).isEqualTo(200);
        assertThat(duplicate.response().body()).isEqualTo("hello".getBytes());
    }
}
