package io.github.josipmusa.core;

import io.github.josipmusa.core.exception.IdempotencyLockTimeoutException;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class IdempotencyEngine {

    private final IdempotencyStore store;
    private final ScheduledExecutorService scheduler;

    public IdempotencyEngine(IdempotencyStore store, ScheduledExecutorService scheduler) {
        this.store = Objects.requireNonNull(store);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    /**
     * Executes the given action if the key is new, or returns the stored
     * response if the key has already been completed.
     *
     * <p>When {@link ExecutionResult.Executed} is returned, the caller is
     * responsible for capturing the response and calling
     * {@link IdempotencyStore#complete} before writing to the client.
     * Failure to do so will leave the key IN_PROGRESS until the lock
     * expires, at which point a subsequent request may re-execute the action.
     */
    public ExecutionResult execute(IdempotencyContext context, ThrowingRunnable action) throws Exception {
        return switch (store.tryAcquire(context)) {
            case AcquireResult.Acquired ignored -> runWithHeartbeat(context, action);
            case AcquireResult.Duplicate d -> new ExecutionResult.Duplicate(d.response());
            case AcquireResult.LockTimeout ignored -> throw new IdempotencyLockTimeoutException(
                    context.key(), context.lockTimeout());
        };
    }

    private ExecutionResult runWithHeartbeat(IdempotencyContext context, ThrowingRunnable action) throws Exception {
        ScheduledFuture<?> heartbeat = startHeartbeat(context);
        try {
            action.run();
            return new ExecutionResult.Executed();
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
