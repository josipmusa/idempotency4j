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

import io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener.FailurePhase;
import io.github.josipmusa.idempotency.core.exception.IdempotencyFingerprintMismatchException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyLockTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *       a heartbeat, calls {@code release} on failure. Does NOT decide when a key
 *       is complete: the adapter calls {@link #complete} once it knows what to store.</li>
 *   <li><strong>Adapter</strong> — builds the context, calls {@code engine.execute()},
 *       captures the HTTP response, calls {@code engine.complete()} with the lease.</li>
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
 *
 * <h2>Lifecycle callbacks</h2>
 * <p>Registered {@link IdempotencyLifecycleListener}s observe each execution
 * synchronously on the calling thread. See that interface for the callback
 * contract; the engine guarantees exactly one terminal callback per acquired
 * lease and never lets a listener influence store state, the return value, or
 * the exception being propagated.
 */
public final class IdempotencyEngine {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyEngine.class);

    private final IdempotencyStore store;
    private final ScheduledExecutorService scheduler;
    private final List<IdempotencyLifecycleListener> listeners;

    /**
     * @param store     the persistence backend for idempotency keys
     * @param scheduler used to schedule heartbeat tasks — should be shared
     *                  across engine instances, not created per-request
     */
    public IdempotencyEngine(IdempotencyStore store, ScheduledExecutorService scheduler) {
        this(store, scheduler, List.of());
    }

    /**
     * @param store     the persistence backend for idempotency keys
     * @param scheduler used to schedule heartbeat tasks - should be shared
     *                  across engine instances, not created per-request
     * @param listeners lifecycle observers, invoked in the given order on the
     *                  calling thread; copied defensively
     */
    public IdempotencyEngine(
            IdempotencyStore store, ScheduledExecutorService scheduler, List<IdempotencyLifecycleListener> listeners) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners must not be null"));
    }

    /**
     * Executes the given action idempotently.
     *
     * <p>If the key is new, acquires the lock, starts a heartbeat, and runs the
     * action. If the key was already completed, returns the stored payload
     * without running the action. If the key is in-flight and the lock timeout
     * is exceeded, throws {@link IdempotencyLockTimeoutException}.
     *
     * <p>When {@link ExecutionResult.Executed} is returned, the <strong>caller</strong>
     * (adapter) is responsible for:
     * <ol>
     *   <li>Capturing whatever the action produced (an HTTP response, or nothing)</li>
     *   <li>Calling {@link #complete} with the returned lease and that payload</li>
     * </ol>
     * If {@code complete} throws after a successful execution, the response has
     * already been produced and should still be sent to the client. The store's state
     * may be indeterminate: a failure can happen before a mutation or, for example,
     * while waiting for a Redis replica after the primary accepted it. Callers must not
     * treat idempotency storage as a transaction around the business side effect.
     *
     * @param context fully resolved idempotency context (identity, ttl, lockTimeout)
     * @param action  the business logic to execute — only runs for new keys
     * @return {@link ExecutionResult.Executed} if the action ran, or
     *         {@link ExecutionResult.Duplicate} with the stored payload
     * @throws IdempotencyLockTimeoutException if the key is in-flight and the
     *         lock timeout expired while waiting
     * @throws Exception if the action itself throws — the original exception
     *         propagates unchanged, and the key is released for retry
     */
    public ExecutionResult execute(IdempotencyContext context, ThrowingRunnable action) throws Exception {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(action, "action must not be null");
        return switch (store.tryAcquire(context)) {
            case AcquireResult.Acquired(String leaseId) -> runWithHeartbeat(context, leaseId, action);
            case AcquireResult.Duplicate(IdempotencyPayload payload) -> {
                notify("onDuplicate", context, listener -> listener.onDuplicate(context, payload));
                yield ExecutionResult.duplicate(payload);
            }
            case AcquireResult.LockTimeout ignored ->
                throw new IdempotencyLockTimeoutException(context.identity(), context.lockTimeout());
            case AcquireResult.FingerprintMismatch(String storedFingerprint, String receivedFingerprint) ->
                throw new IdempotencyFingerprintMismatchException(
                        context.identity(), storedFingerprint, receivedFingerprint);
        };
    }

    /**
     * Records the completion of an executed action, closing the idempotent boundary.
     *
     * <p>Delegates to {@link IdempotencyStore#complete} and adds the lifecycle
     * callback the store SPI knows nothing about: {@link IdempotencyLifecycleListener#onCompleted}
     * once the store has confirmed the transition, or
     * {@link IdempotencyLifecycleListener#onFailed} with
     * {@link FailurePhase#COMPLETION} if it did not. Store exceptions are rethrown
     * unchanged, so callers keep whatever handling they already had around
     * {@code store.complete}.
     *
     * <p>Call this exactly once per {@link ExecutionResult.Executed}, passing that
     * result's lease. The engine does not extend the lock here - {@link #execute}
     * already did so after the action returned.
     *
     * @param context the context the execution ran under
     * @param leaseId the lease from the {@link ExecutionResult.Executed} being completed
     * @param payload what to store for a duplicate to replay
     * @param ttl     how long to keep the completed entry before expiry
     * @throws io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException
     *         if this execution no longer owns the key
     * @throws io.github.josipmusa.idempotency.core.exception.IdempotencyDurabilityException
     *         if the mutation was accepted but requested durability could not be confirmed
     */
    public void complete(IdempotencyContext context, String leaseId, IdempotencyPayload payload, Duration ttl) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        try {
            store.complete(context.identity(), leaseId, payload, ttl);
        } catch (Exception e) {
            notifyFailed(context, leaseId, e, FailurePhase.COMPLETION);
            throw e;
        }
        notify("onCompleted", context, listener -> listener.onCompleted(context, leaseId, payload));
    }

    /**
     * Runs the action with an active heartbeat and releases the lock if the action throws.
     *
     * <p>The {@link ScheduledExecutorService} is intentionally <em>not</em> owned by the engine.
     * Callers should share a single scheduler across all engine instances and shut it down when
     * the application stops (e.g., via a Spring {@code @PreDestroy} method or a
     * {@link java.io.Closeable} wrapper).
     *
     * <p>{@code onAcquired} fires once the heartbeat is running and the action is
     * genuinely about to execute. A lease whose heartbeat could not be scheduled is
     * released here and reported to nobody: firing {@code onAcquired} for an execution
     * that never happens would leave a listener holding per-request state with no
     * terminal callback to release it.
     */
    private ExecutionResult runWithHeartbeat(IdempotencyContext context, String leaseId, ThrowingRunnable action)
            throws Exception {
        ScheduledFuture<?> heartbeat;
        try {
            heartbeat = startHeartbeat(context, leaseId);
        } catch (RuntimeException schedulingFailure) {
            try {
                store.release(context.identity(), leaseId);
            } catch (Exception releaseFailure) {
                schedulingFailure.addSuppressed(releaseFailure);
            }
            throw schedulingFailure;
        }
        try {
            notify("onAcquired", context, listener -> listener.onAcquired(context, leaseId));
            action.run();
            try {
                store.extendLock(context.identity(), leaseId, context.lockTimeout());
            } catch (Exception ignored) {
                // Best-effort: this final extension only buys the adapter time to call
                // complete(). The lease is still valid, and complete() fences on it anyway,
                // so a failure here must not turn a successful action into a failed one.
            }
            return ExecutionResult.executed(leaseId);
        } catch (Exception e) {
            try {
                store.release(context.identity(), leaseId);
            } catch (Exception releaseEx) {
                e.addSuppressed(releaseEx);
            }
            notifyFailed(context, leaseId, e, FailurePhase.ACTION);
            throw e;
        } finally {
            heartbeat.cancel(false);
        }
    }

    private ScheduledFuture<?> startHeartbeat(IdempotencyContext context, String leaseId) {
        long intervalMs = context.lockTimeout().dividedBy(2).toMillis();
        return scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        store.extendLock(context.identity(), leaseId, context.lockTimeout());
                    } catch (Exception e) {
                        // heartbeat failure is non-fatal - lock will eventually
                        // expire naturally and be stolen by a waiting request
                    }
                },
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS);
    }

    private void notifyFailed(IdempotencyContext context, String leaseId, Throwable cause, FailurePhase phase) {
        notify("onFailed", context, listener -> listener.onFailed(context, leaseId, cause, phase));
    }

    /**
     * Invokes one callback on every listener, in registration order.
     *
     * <p>A listener is not allowed to influence the execution it is observing, so
     * anything it throws is logged and dropped and the remaining listeners still
     * run. An {@link Error} is left to propagate: it signals a problem this engine
     * must not hide.
     */
    private void notify(
            String callback, IdempotencyContext context, Consumer<IdempotencyLifecycleListener> invocation) {
        for (IdempotencyLifecycleListener listener : listeners) {
            try {
                invocation.accept(listener);
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                log.warn(
                        "Idempotency lifecycle listener {} threw from {} for {}; ignoring",
                        listener.getClass().getName(),
                        callback,
                        context.identity(),
                        t);
            }
        }
    }
}
