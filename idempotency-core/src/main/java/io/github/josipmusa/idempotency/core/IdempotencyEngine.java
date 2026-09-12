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
import io.github.josipmusa.idempotency.core.exception.IdempotencyRollbackException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs an action at most once per identity: acquires the lease, executes, records the
 * completion, and reports what happened as an {@link Outcome}.
 *
 * <p>The engine is framework-agnostic - it knows nothing about HTTP, Spring, or databases.
 * It delegates persistence to an {@link IdempotencyStore} and receives a fully resolved
 * {@link IdempotencyContext} from the adapter layer.
 *
 * <h2>Responsibility boundaries</h2>
 * <ul>
 *   <li><strong>Engine</strong> - owns the whole lifecycle. Calls {@code tryAcquire}, runs
 *       the action under a heartbeat, encodes its result, calls {@code complete}, and calls
 *       {@code release} if the action throws. Nothing is left for the caller to record.</li>
 *   <li><strong>Adapter</strong> - builds the context, supplies the action and a
 *       {@link PayloadCodec} for whatever the action produces, and translates the returned
 *       {@link Outcome} into its transport.</li>
 *   <li><strong>Store</strong> - handles persistence, blocking, and lease stealing.</li>
 * </ul>
 *
 * <h2>Heartbeat</h2>
 * <p>While the action runs, a background task calls {@link IdempotencyStore#extendLock} at
 * half the lease duration (e.g. every 5s for a 10s lease). This prevents the lease from
 * being stolen while the action is legitimately still running. The heartbeat is cancelled
 * in the {@code finally} block regardless of success or failure.
 *
 * <h2>Failure handling</h2>
 * <p>If the action throws anything at all - including an {@link Error} - the engine calls
 * {@link IdempotencyStore#release} to delete the record so the key can be retried, and the
 * failed attempt leaves no trace. If {@code release} itself throws (e.g. the store is down),
 * the release failure is added as a suppressed exception on the original - what the action
 * threw always propagates as the primary.
 *
 * <p>If the action succeeded but the completion could not be recorded, the lease is
 * <em>not</em> released: the work happened, so deleting the record would advertise a key
 * that was never used. What the engine does next is
 * {@link CompletionFailurePolicy the configured policy}.
 *
 * <h2>Completion modes</h2>
 * <p>By default the completion is recorded on its own, the moment the action returns. A
 * context asking for {@link CompletionMode#JOIN_TRANSACTION} instead has the engine call
 * {@code complete} inside the transaction the action is already running in, so the record and
 * the action's writes commit together and a crash in between leaves neither. That mode needs
 * an active transaction at entry - the engine throws {@link IllegalStateException} otherwise -
 * and a store that supports it, which the constructor checks. The terminal callback moves with
 * the record: {@code onCompleted} after the commit, or a release plus
 * {@link FailurePhase#ROLLBACK} after a rollback.
 *
 * <h2>Lifecycle callbacks</h2>
 * <p>Registered {@link IdempotencyLifecycleListener}s observe each execution synchronously
 * on the calling thread. See that interface for the callback contract; the engine guarantees
 * exactly one terminal callback per acquired lease and never lets a listener influence store
 * state, the return value, or the exception being propagated.
 */
public final class IdempotencyEngine {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyEngine.class);

    private final IdempotencyStore store;
    private final ScheduledExecutorService scheduler;
    private final List<IdempotencyLifecycleListener> listeners;
    private final CompletionFailurePolicy completionFailurePolicy;
    private final TransactionParticipation transactions;

    /**
     * @param store     the persistence backend for idempotency keys
     * @param scheduler used to schedule heartbeat tasks - should be shared
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
        this(store, scheduler, listeners, IdempotencyConfig.defaults());
    }

    /**
     * @param store     the persistence backend for idempotency keys
     * @param scheduler used to schedule heartbeat tasks - should be shared
     *                  across engine instances, not created per-request
     * @param listeners lifecycle observers, invoked in the given order on the
     *                  calling thread; copied defensively
     * @param config    application defaults; the engine reads only
     *                  {@link IdempotencyConfig#completionFailurePolicy()} from it, because
     *                  everything else is already resolved into the context it is handed
     */
    public IdempotencyEngine(
            IdempotencyStore store,
            ScheduledExecutorService scheduler,
            List<IdempotencyLifecycleListener> listeners,
            IdempotencyConfig config) {
        this(store, scheduler, listeners, config, TransactionParticipation.none());
    }

    /**
     * @param store        the persistence backend for idempotency keys
     * @param scheduler    used to schedule heartbeat tasks - should be shared
     *                     across engine instances, not created per-request
     * @param listeners    lifecycle observers, invoked in the given order on the
     *                     calling thread; copied defensively
     * @param config       application defaults; the engine reads only
     *                     {@link IdempotencyConfig#completionFailurePolicy()} from it, because
     *                     everything else is already resolved into the context it is handed
     * @param transactions how the engine sees the caller's transaction, for contexts asking
     *                     for {@link CompletionMode#JOIN_TRANSACTION};
     *                     {@link TransactionParticipation#none()} disables joined completion
     * @throws IllegalArgumentException if a real {@code transactions} is supplied for a store
     *         whose {@link IdempotencyStore#supportsTransactionalCompletion()} is {@code false}
     */
    public IdempotencyEngine(
            IdempotencyStore store,
            ScheduledExecutorService scheduler,
            List<IdempotencyLifecycleListener> listeners,
            IdempotencyConfig config,
            TransactionParticipation transactions) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners must not be null"));
        this.completionFailurePolicy =
                Objects.requireNonNull(config, "config must not be null").completionFailurePolicy();
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        if (transactions != TransactionParticipation.none() && !store.supportsTransactionalCompletion()) {
            throw new IllegalArgumentException("Store " + store.getClass().getName()
                    + " does not support transactional completion, so it cannot be given a "
                    + "TransactionParticipation. Use TransactionParticipation.none(), or a store whose "
                    + "supportsTransactionalCompletion() is true.");
        }
    }

    /**
     * Executes the given action at most once per identity and returns what happened.
     *
     * <p>If the key is new, the engine acquires the lease, starts the heartbeat, runs the
     * action, encodes its result with {@code codec}, records the completion, and returns
     * {@link Outcome.Executed}. If the key was already completed, the action does not run
     * and the stored payload comes back decoded as {@link Outcome.Replayed}. If another
     * caller holds the key and does not finish within the context's {@code waitTimeout},
     * the action does not run and the engine returns {@link Outcome.InFlight}.
     *
     * @param context fully resolved idempotency context (identity, ttl, lease, wait)
     * @param action  the business logic to execute - only runs for a new key
     * @param codec   translates the action's result to and from the stored payload
     * @param <T>     what the action produces
     * @return what happened; never {@code null}
     * @throws IdempotencyFingerprintMismatchException if the key was already used with a
     *         different request body
     * @throws Exception if the action itself throws - the original exception propagates
     *         unchanged, and the record is deleted so the key can be retried. An
     *         {@link Error} is handled the same way and propagates too. A failure to record
     *         the completion propagates only under
     *         {@link CompletionFailurePolicy#PROPAGATE}
     */
    public <T> Outcome<T> execute(IdempotencyContext context, ThrowingSupplier<T> action, PayloadCodec<T> codec)
            throws Exception {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(codec, "codec must not be null");
        requireTransactionForJoinedMode(context);
        return switch (store.tryAcquire(context)) {
            case AcquireResult.Acquired(String leaseId) -> runWithHeartbeat(context, leaseId, action, codec);
            case AcquireResult.Duplicate(Payload payload, Instant completedAt) -> {
                notify("onDuplicate", context, listener -> listener.onDuplicate(context, payload, completedAt));
                yield new Outcome.Replayed<>(codec.decode(payload), completedAt);
            }
            case AcquireResult.InFlight(Duration retryAfter) -> {
                notify("onInFlight", context, listener -> listener.onInFlight(context, retryAfter));
                yield new Outcome.InFlight<>(retryAfter);
            }
            case AcquireResult.FingerprintMismatch(String storedFingerprint, String receivedFingerprint) ->
                throw new IdempotencyFingerprintMismatchException(
                        context.identity(), storedFingerprint, receivedFingerprint);
        };
    }

    /**
     * Executes an action with nothing to replay.
     *
     * <p>Equivalent to
     * {@link #execute(IdempotencyContext, ThrowingSupplier, PayloadCodec)} with
     * {@link PayloadCodec#none()}: a duplicate is still recognised and the action still runs
     * at most once, but {@link Payload#none()} is what gets stored and
     * {@link Outcome.Replayed#value()} is always {@code null}.
     *
     * @param context fully resolved idempotency context (identity, ttl, lease, wait)
     * @param action  the business logic to execute - only runs for a new key
     * @return what happened; never {@code null}
     * @throws IdempotencyFingerprintMismatchException if the key was already used with a
     *         different request body
     * @throws Exception if the action itself throws, or if the completion could not be
     *         recorded under {@link CompletionFailurePolicy#PROPAGATE}
     */
    public Outcome<Void> execute(IdempotencyContext context, ThrowingRunnable action) throws Exception {
        Objects.requireNonNull(action, "action must not be null");
        return execute(
                context,
                () -> {
                    action.run();
                    return null;
                },
                PayloadCodec.none());
    }

    /**
     * Runs the action with an active heartbeat, then records the completion.
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
    private <T> Outcome<T> runWithHeartbeat(
            IdempotencyContext context, String leaseId, ThrowingSupplier<T> action, PayloadCodec<T> codec)
            throws Exception {
        ScheduledFuture<?> heartbeat;
        try {
            heartbeat = startHeartbeat(context, leaseId);
        } catch (Throwable schedulingFailure) {
            releaseQuietly(context, leaseId, schedulingFailure);
            throw schedulingFailure;
        }
        T value;
        try {
            notify("onAcquired", context, listener -> listener.onAcquired(context, leaseId));
            value = action.get();
            extendLeaseForCompletion(context, leaseId);
        } catch (Throwable t) {
            releaseQuietly(context, leaseId, t);
            notifyFailed(context, leaseId, t, FailurePhase.ACTION);
            throw t;
        } finally {
            heartbeat.cancel(false);
        }
        return complete(context, leaseId, value, codec);
    }

    /**
     * Encodes the action's result and records the completion, closing the idempotent boundary.
     *
     * <p>The lease is deliberately not released when this fails: the action ran and its side
     * effects are durable, so deleting the record would advertise a key that was never used.
     * The record stays IN_PROGRESS until its lease expires, at which point a retry can steal
     * it and run the action again - which is the honest outcome, because nothing was recorded.
     */
    private <T> Outcome<T> complete(IdempotencyContext context, String leaseId, T value, PayloadCodec<T> codec) {
        Payload payload;
        try {
            payload = codec.encode(value);
            store.complete(context.identity(), leaseId, payload, context.ttl());
        } catch (Exception e) {
            notifyFailed(context, leaseId, e, FailurePhase.COMPLETION);
            if (completionFailurePolicy == CompletionFailurePolicy.PROPAGATE) {
                throw e;
            }
            log.error(
                    "Action for {} succeeded but its completion could not be recorded; storage state is indeterminate and a later duplicate will re-execute",
                    context.identity(),
                    e);
            return new Outcome.Executed<>(value);
        } catch (Throwable t) {
            notifyFailed(context, leaseId, t, FailurePhase.COMPLETION);
            throw t;
        }
        if (context.completionMode() == CompletionMode.JOIN_TRANSACTION) {
            deferTerminalToTransaction(context, leaseId, payload);
        } else {
            notify("onCompleted", context, listener -> listener.onCompleted(context, leaseId, payload));
        }
        return new Outcome.Executed<>(value);
    }

    /**
     * Rejects a joined-mode context that arrives without a transaction to join.
     *
     * <p>Checked before the lease is acquired, so the caller's mistake costs nothing and
     * leaves no record behind. Running the action autonomously instead would silently give up
     * the guarantee the context asked for, which is the one thing worse than failing.
     */
    private void requireTransactionForJoinedMode(IdempotencyContext context) {
        if (context.completionMode() == CompletionMode.JOIN_TRANSACTION && !transactions.active()) {
            throw new IllegalStateException("Context for " + context.identity()
                    + " asks for CompletionMode.JOIN_TRANSACTION but no transaction is active on this thread. "
                    + "Start the transaction around the engine call, or use CompletionMode.AUTONOMOUS.");
        }
    }

    /**
     * Hands the terminal callback to the caller's transaction.
     *
     * <p>{@code store.complete} has already run, but inside the caller's transaction, so
     * nothing is durable yet: the record becomes COMPLETE on commit and vanishes back to
     * IN_PROGRESS on rollback. Announcing {@code onCompleted} now would be a lie a rollback
     * could not take back, so both terminals wait for the outcome and exactly one of them
     * fires - the invariant is preserved, just later.
     *
     * <p>On rollback the record is left IN_PROGRESS with a live lease, so the engine releases
     * it. That call runs after the transaction has finished, so it reaches the store on a
     * connection of the store's own - the same autonomous path the action-failure release
     * takes.
     */
    private void deferTerminalToTransaction(IdempotencyContext context, String leaseId, Payload payload) {
        transactions.afterCommit(
                () -> notify("onCompleted", context, listener -> listener.onCompleted(context, leaseId, payload)));
        transactions.afterRollback(() -> {
            IdempotencyRollbackException rollback = new IdempotencyRollbackException(context.identity());
            releaseQuietly(context, leaseId, rollback);
            notifyFailed(context, leaseId, rollback, FailurePhase.ROLLBACK);
        });
    }

    /**
     * Best-effort final lease extension, run once the action has returned.
     *
     * <p>This only buys the engine time to encode the result and record the completion. The
     * lease is still valid, and {@code complete()} fences on it anyway, so a failure here
     * must not turn a successful action into a failed one.
     */
    private void extendLeaseForCompletion(IdempotencyContext context, String leaseId) {
        try {
            store.extendLock(context.identity(), leaseId, context.leaseDuration());
        } catch (Exception ignored) {
            // see above - deliberately swallowed
        }
    }

    private void releaseQuietly(IdempotencyContext context, String leaseId, Throwable primary) {
        try {
            store.release(context.identity(), leaseId);
        } catch (Throwable releaseFailure) {
            primary.addSuppressed(releaseFailure);
        }
    }

    private ScheduledFuture<?> startHeartbeat(IdempotencyContext context, String leaseId) {
        long intervalMs = context.leaseDuration().dividedBy(2).toMillis();
        return scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        store.extendLock(context.identity(), leaseId, context.leaseDuration());
                    } catch (Exception e) {
                        // heartbeat failure is non-fatal - the lease will eventually
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
            } catch (Exception e) {
                log.warn(
                        "Idempotency lifecycle listener {} threw from {} for {}; ignoring",
                        listener.getClass().getName(),
                        callback,
                        context.identity(),
                        e);
            }
        }
    }
}
