package io.github.josipmusa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.github.josipmusa.core.exception.IdempotencyLockTimeoutException;
import io.github.josipmusa.core.exception.IdempotencyStoreException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdempotencyEngineTest {

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

    private IdempotencyContext defaultContext(String key) {
        return new IdempotencyContext(key, Duration.ofHours(1), Duration.ofSeconds(5));
    }

    private StoredResponse anyStoredResponse() {
        return new StoredResponse(
                200, Map.of("Content-Type", List.of("application/json")), "{\"id\":\"123\"}".getBytes(), Instant.now());
    }

    @Test
    void newKey_returnsExecuted() throws Exception {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired());

        ExecutionResult result = engine.execute(defaultContext("new-key"), () -> {});

        assertThat(result).isInstanceOf(ExecutionResult.Executed.class);
    }

    @Test
    void completedKey_returnsDuplicate() throws Exception {
        StoredResponse response = anyStoredResponse();
        when(store.tryAcquire(any())).thenReturn(AcquireResult.duplicate(response));

        ExecutionResult result = engine.execute(defaultContext("done-key"), () -> {});

        assertThat(result).isInstanceOf(ExecutionResult.Duplicate.class);
        ExecutionResult.Duplicate duplicate = (ExecutionResult.Duplicate) result;
        assertThat(duplicate.response()).isSameAs(response);
    }

    @Test
    void completedKey_actionNotCalledOnDuplicate() throws Exception {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.duplicate(anyStoredResponse()));
        AtomicInteger counter = new AtomicInteger(0);

        engine.execute(defaultContext("dup-key"), counter::incrementAndGet);

        assertThat(counter.get()).isZero();
    }

    @Test
    void actionThrows_releaseIsCalled() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired());
        String key = "fail-key";

        try {
            engine.execute(defaultContext(key), () -> {
                throw new RuntimeException("boom");
            });
        } catch (Exception ignored) {
        }

        verify(store, times(1)).release(key);
    }

    @Test
    void actionThrows_completeIsNeverCalled() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired());

        try {
            engine.execute(defaultContext("fail-key"), () -> {
                throw new RuntimeException("boom");
            });
        } catch (Exception ignored) {
        }

        verify(store, never()).complete(any(), any(), any());
    }

    @Test
    void actionThrows_originalExceptionPropagates() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired());
        RuntimeException expected = new RuntimeException("specific failure");

        assertThatThrownBy(() -> engine.execute(defaultContext("fail-key"), () -> {
                    throw expected;
                }))
                .isSameAs(expected);
    }

    @Test
    void lockTimeout_throwsIdempotencyLockTimeoutException() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.lockTimeout("test-key"));

        assertThatThrownBy(() -> engine.execute(defaultContext("test-key"), () -> {}))
                .isInstanceOf(IdempotencyLockTimeoutException.class)
                .satisfies(e -> {
                    IdempotencyLockTimeoutException ex = (IdempotencyLockTimeoutException) e;
                    assertThat(ex.getKey()).isEqualTo("test-key");
                });
    }

    @Test
    void heartbeat_extendLockCalledDuringLongRunningAction() throws Exception {
        IdempotencyContext context = new IdempotencyContext("hb-key", Duration.ofHours(1), Duration.ofMillis(100));
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired());

        engine.execute(context, () -> Thread.sleep(300));

        verify(store, atLeastOnce()).extendLock(eq("hb-key"), eq(Duration.ofMillis(100)));
    }

    @Test
    void heartbeat_stopsAfterActionCompletes() throws Exception {
        IdempotencyContext context = new IdempotencyContext("hb-stop-key", Duration.ofHours(1), Duration.ofMillis(100));
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired());

        engine.execute(context, () -> {});

        // Wait briefly to let any in-flight heartbeat fire
        Thread.sleep(50);
        int countAfterExecute = mockingDetails(store).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("extendLock"))
                .toList()
                .size();

        // Wait 2x the heartbeat interval (50ms interval for 100ms lockTimeout)
        Thread.sleep(100);
        int countAfterWait = mockingDetails(store).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("extendLock"))
                .toList()
                .size();

        assertThat(countAfterWait).isEqualTo(countAfterExecute);
    }

    @Test
    void heartbeat_stopsAfterActionThrows() throws Exception {
        IdempotencyContext context =
                new IdempotencyContext("hb-throw-key", Duration.ofHours(1), Duration.ofMillis(100));
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired());

        try {
            engine.execute(context, () -> {
                throw new RuntimeException("fail");
            });
        } catch (Exception ignored) {
        }

        Thread.sleep(50);
        int countAfterExecute = mockingDetails(store).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("extendLock"))
                .toList()
                .size();

        Thread.sleep(100);
        int countAfterWait = mockingDetails(store).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("extendLock"))
                .toList()
                .size();

        assertThat(countAfterWait).isEqualTo(countAfterExecute);
    }

    // --- Context forwarding ---

    @Test
    void execute_forwardsContextToStore() throws Exception {
        IdempotencyContext context = defaultContext("forwarded-key");
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired());

        engine.execute(context, () -> {});

        verify(store).tryAcquire(eq(context));
    }

    // --- Checked exception propagation ---

    @Test
    void actionThrowsCheckedException_propagatesUnwrapped() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired());
        IOException expected = new IOException("disk full");

        assertThatThrownBy(() -> engine.execute(defaultContext("checked-key"), () -> {
                    throw expected;
                }))
                .isSameAs(expected);
    }

    // --- Cascading failure: release throws after action failure ---

    @Test
    void actionThrowsAndReleaseFails_originalExceptionPropagates() {
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired());
        RuntimeException actionException = new RuntimeException("action failed");
        IdempotencyStoreException releaseException = new IdempotencyStoreException("store unreachable");
        doThrow(releaseException).when(store).release(any());

        assertThatThrownBy(() -> engine.execute(defaultContext("cascade-key"), () -> {
                    throw actionException;
                }))
                .isSameAs(actionException)
                .satisfies(e -> assertThat(e.getSuppressed()).contains(releaseException));
    }

    // --- Store failure on tryAcquire ---

    @Test
    void storeThrowsOnTryAcquire_propagatesDirectly() {
        IdempotencyStoreException storeFailure = new IdempotencyStoreException("connection refused");
        when(store.tryAcquire(any())).thenThrow(storeFailure);

        assertThatThrownBy(() -> engine.execute(defaultContext("store-fail-key"), () -> {}))
                .isSameAs(storeFailure);

        // Heartbeat should never have started
        verify(store, never()).extendLock(any(), any());
    }
}
