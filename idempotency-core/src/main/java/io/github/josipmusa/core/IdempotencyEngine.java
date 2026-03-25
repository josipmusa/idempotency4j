package io.github.josipmusa.core;

import io.github.josipmusa.core.exception.IdempotencyLockTimeoutException;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the idempotency lifecycle: acquire lock, run action, manage heartbeat.
 *
 * <p>The engine is framework-agnostic — it knows nothing about HTTP, Spring, or
 * databases. It delegates persistence to an {@link IdempotencyStore} and receives
 * a fully resolved {@link IdempotencyContext} from the adapter layer.
 *
 * <h2>Responsibility boundaries</h2>
 * <ul>
 *   <li><strong>Engine</strong> — calls {@code tryAcquire}, runs the action with
 *       a heartbeat, calls {@code release} on failure. Does NOT call {@code complete}.</li>
 *   <li><strong>Adapter</strong> — builds the context, calls {@code engine.execute()},
 *       captures the HTTP response, calls {@code store.complete()}.</li>
 *   <li><strong>Store</strong> — handles persistence, blocking, and lock-stealing.</li>
 * </ul>
 *
 * <h2>Heartbeat</h2>
 * <p>While the action runs, a background task calls
 * {@link IdempotencyStore#extendLock} at half the lock timeout interval
 * (e.g. every 5s for a 10s lock). This prevents the lock from being stolen
 * while the action is legitimately still running. The heartbeat is cancelled
 * in the {@code finally} block regardless of success or failure.
 *
 * <h2>Failure handling</h2>
 * <p>If the action throws, the engine calls {@link IdempotencyStore#release}
 * to transition the key to FAILED so it can be retried. If {@code release}
 * itself throws (e.g. store is down), the release exception is added as a
 * suppressed exception on the original — the action's exception always
 * propagates as the primary.
 */
public final class IdempotencyEngine {

    private final IdempotencyStore store;
    private final ScheduledExecutorService scheduler;

    /**
     * @param store     the persistence backend for idempotency keys
     * @param scheduler used to schedule heartbeat tasks — should be shared
     *                  across engine instances, not created per-request
     */
    public IdempotencyEngine(IdempotencyStore store, ScheduledExecutorService scheduler) {
        this.store = Objects.requireNonNull(store);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    /**
     * Executes the given action idempotently.
     *
     * <p>If the key is new, acquires the lock, starts a heartbeat, and runs the
     * action. If the key was already completed, returns the stored response
     * without running the action. If the key is in-flight and the lock timeout
     * is exceeded, throws {@link IdempotencyLockTimeoutException}.
     *
     * <p>When {@link ExecutionResult.Executed} is returned, the <strong>caller</strong>
     * (adapter) is responsible for:
     * <ol>
     *   <li>Capturing the HTTP response produced by the action</li>
     *   <li>Calling {@link IdempotencyStore#complete} with that response</li>
     * </ol>
     * Failure to complete will leave the key IN_PROGRESS until the lock expires,
     * at which point a subsequent request will steal the lock and re-execute.
     *
     * @param context fully resolved idempotency context (key, ttl, lockTimeout)
     * @param action  the business logic to execute — only runs for new keys
     * @return {@link ExecutionResult.Executed} if the action ran, or
     *         {@link ExecutionResult.Duplicate} with the stored response
     * @throws IdempotencyLockTimeoutException if the key is in-flight and the
     *         lock timeout expired while waiting
     * @throws Exception if the action itself throws — the original exception
     *         propagates unchanged, and the key is released for retry
     */
    public ExecutionResult execute(IdempotencyContext context, ThrowingRunnable action) throws Exception {
        return switch (store.tryAcquire(context)) {
            case AcquireResult.Acquired ignored -> runWithHeartbeat(context, action);
            case AcquireResult.Duplicate d -> ExecutionResult.duplicate(d.response());
            case AcquireResult.LockTimeout ignored -> throw new IdempotencyLockTimeoutException(
                    context.key(), context.lockTimeout());
        };
    }

    private ExecutionResult runWithHeartbeat(IdempotencyContext context, ThrowingRunnable action) throws Exception {
        ScheduledFuture<?> heartbeat = startHeartbeat(context);
        try {
            action.run();
            return ExecutionResult.executed();
        } catch (Exception e) {
            try {
                store.release(context.key());
            } catch (Exception releaseEx) {
                e.addSuppressed(releaseEx);
            }
            throw e;
        } finally {
            heartbeat.cancel(false);
        }
    }

    private ScheduledFuture<?> startHeartbeat(IdempotencyContext context) {
        long intervalMs = context.lockTimeout().dividedBy(2).toMillis();
        return scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        store.extendLock(context.key(), context.lockTimeout());
                    } catch (Exception e) {
                        // heartbeat failure is non-fatal - lock will eventually
                        // expire naturally and be stolen by a waiting request
                    }
                },
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS);
    }
}
