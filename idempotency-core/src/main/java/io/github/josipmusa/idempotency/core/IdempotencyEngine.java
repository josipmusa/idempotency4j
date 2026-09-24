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
import io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException;
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
 * once the lease's fate is settled: when the action fails, when the completion is recorded, or -
 * for an autonomous completion waiting on the caller's transaction - when that transaction ends.
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
 * {@link CompletionFailurePolicy the configured policy}, except under
 * {@link CompletionMode#JOIN_TRANSACTION}, where the failure always propagates: returning
 * normally would let the caller's transaction commit its writes without the record, which is
 * the one outcome joined completion exists to rule out. If that transaction then rolls back,
 * the engine releases the lease, because the work it guarded is gone with it.
 *
 * <h2>Completion modes</h2>
 * <p>By default the completion is recorded on its own. With no transaction active when the
 * action returns, that happens at once. With one active, the engine waits for it: the record is
 * written after the commit, and a rollback releases the lease instead - announcing a completion
 * that a rollback then undid would replay work that never happened. A context asking for
 * {@link CompletionMode#JOIN_TRANSACTION} instead has the engine call
 * {@link IdempotencyStore#completeInTransaction} inside the transaction the action is running
 * in, so the record and the action's writes commit together and a crash in between leaves
 * neither. That mode needs an active transaction at entry and a store that supports it - the
 * engine throws {@link IllegalStateException} otherwise, before acquiring anything.
 *
 * <p>Whenever the completion waits on a transaction, the terminal callback waits with it:
 * {@code onCompleted} once the record is durable after the commit, or a release plus
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
     * @param transactions how the engine sees the caller's transaction: it defers an autonomous
     *                     completion until that transaction commits, and joins it for contexts
     *                     asking for {@link CompletionMode#JOIN_TRANSACTION};
     *                     {@link TransactionParticipation#none()} sees no transaction at all, so
     *                     every completion is immediate and joined completion is unavailable
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
     *         the completion propagates under {@link CompletionFailurePolicy#PROPAGATE}, and
     *         always under {@link CompletionMode#JOIN_TRANSACTION}
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
     *         recorded under {@link CompletionFailurePolicy#PROPAGATE} or
     *         {@link CompletionMode#JOIN_TRANSACTION}
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
            heartbeat.cancel(false);
            releaseQuietly(context, leaseId, t);
            notifyFailed(context, leaseId, t, FailurePhase.ACTION);
            throw t;
        }
        if (context.completionMode() == CompletionMode.AUTONOMOUS && transactions.active()) {
            return completeAfterTransaction(context, leaseId, value, codec, heartbeat);
        }
        heartbeat.cancel(false);
        return complete(context, leaseId, value, codec);
    }

    /**
     * Encodes the action's result and records the completion, closing the idempotent boundary.
     *
     * <p>The lease is deliberately not released when this fails: the action ran and its side
     * effects are durable, so deleting the record would advertise a key that was never used.
     * The record stays IN_PROGRESS until its lease expires, at which point a retry can steal
     * it and run the action again - which is the honest outcome, because nothing was recorded.
     *
     * <p>A joined completion failure ignores the policy and always propagates, and the lease is
     * released if the caller's transaction rolls back - see {@link #releaseOnRollback}.
     */
    private <T> Outcome<T> complete(IdempotencyContext context, String leaseId, T value, PayloadCodec<T> codec)
            throws Exception {
        boolean joined = context.completionMode() == CompletionMode.JOIN_TRANSACTION;
        Payload payload;
        try {
            payload = codec.encode(value);
            if (joined) {
                store.completeInTransaction(context.identity(), leaseId, payload, context.ttl());
            } else {
                store.complete(context.identity(), leaseId, payload, context.ttl());
            }
        } catch (Exception e) {
            notifyFailed(context, leaseId, e, FailurePhase.COMPLETION);
            if (joined) {
                releaseOnRollback(context, leaseId);
                throw e;
            }
            return afterCompletionFailure(context, value, e);
        } catch (Throwable t) {
            notifyFailed(context, leaseId, t, FailurePhase.COMPLETION);
            if (joined) {
                releaseOnRollback(context, leaseId);
            }
            throw t;
        }
        if (joined) {
            deferTerminalToTransaction(context, leaseId, payload);
        } else {
            notify("onCompleted", context, listener -> listener.onCompleted(context, leaseId, payload));
        }
        return new Outcome.Executed<>(value);
    }

    /**
     * Records an autonomous completion once the caller's transaction has committed.
     *
     * <p>The action ran inside that transaction, so its work is not durable yet and a rollback
     * can still undo it. Recording the completion now would advertise, to every later duplicate,
     * work that may never happen. So the payload is encoded now - an encoding failure is an
     * ordinary completion failure, reported while the caller can still see it - and written on
     * the store's own connection after the commit. A rollback releases the lease instead, and the
     * key is free to retry.
     *
     * <p>The heartbeat keeps running until then. The transaction can outlive the action by any
     * amount, and a lease that lapsed in between could be stolen, running the action twice.
     *
     * <p>{@link Outcome.Executed} comes back straight away: the caller's answer does not change,
     * only when the record becomes durable does.
     */
    private <T> Outcome<T> completeAfterTransaction(
            IdempotencyContext context, String leaseId, T value, PayloadCodec<T> codec, ScheduledFuture<?> heartbeat)
            throws Exception {
        Payload payload;
        try {
            payload = codec.encode(value);
            transactions.afterCommit(() -> recordAfterCommit(context, leaseId, payload, heartbeat));
            transactions.afterRollback(() -> {
                heartbeat.cancel(false);
                releaseAfterRollback(context, leaseId);
            });
        } catch (Exception e) {
            heartbeat.cancel(false);
            notifyFailed(context, leaseId, e, FailurePhase.COMPLETION);
            return afterCompletionFailure(context, value, e);
        } catch (Throwable t) {
            heartbeat.cancel(false);
            notifyFailed(context, leaseId, t, FailurePhase.COMPLETION);
            throw t;
        }
        return new Outcome.Executed<>(value);
    }

    /**
     * Writes a deferred autonomous completion, from the transaction's after-commit callback.
     *
     * <p>A failure here has nowhere to propagate - the caller already has its answer - so it is
     * logged and reported as {@link FailurePhase#COMPLETION} whatever the policy says. The lease
     * is not released: the work committed, and the record stays IN_PROGRESS until the lease
     * expires, exactly as for an immediate completion failure.
     */
    private void recordAfterCommit(
            IdempotencyContext context, String leaseId, Payload payload, ScheduledFuture<?> heartbeat) {
        heartbeat.cancel(false);
        try {
            store.complete(context.identity(), leaseId, payload, context.ttl());
        } catch (Exception e) {
            log.error(
                    "Action for {} committed but its completion could not be recorded; a later duplicate will re-execute",
                    context.identity(),
                    e);
            notifyFailed(context, leaseId, e, FailurePhase.COMPLETION);
            return;
        } catch (Throwable t) {
            notifyFailed(context, leaseId, t, FailurePhase.COMPLETION);
            throw t;
        }
        notify("onCompleted", context, listener -> listener.onCompleted(context, leaseId, payload));
    }

    /**
     * Applies the {@link CompletionFailurePolicy} to an autonomous completion that failed while
     * the caller is still waiting for its answer. The failure has already been reported.
     */
    private <T> Outcome<T> afterCompletionFailure(IdempotencyContext context, T value, Exception exception)
            throws Exception {
        if (completionFailurePolicy == CompletionFailurePolicy.PROPAGATE) {
            throw exception;
        }
        log.error(
                "Action for {} succeeded but its completion could not be recorded; storage state is indeterminate and a later duplicate will re-execute",
                context.identity(),
                exception);
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
        if (context.completionMode() != CompletionMode.JOIN_TRANSACTION) {
            return;
        }
        // Ask the store first. A store that cannot enlist in a caller's transaction is wired
        // with TransactionParticipation.none(), whose active() is always false - so checking
        // the transaction first would blame the caller for a missing transaction it did in
        // fact open, and send them hunting advisor ordering for a problem that is not there.
        if (!store.supportsTransactionalCompletion()) {
            throw new IllegalStateException("Context for " + context.identity()
                    + " asks for CompletionMode.JOIN_TRANSACTION but "
                    + store.getClass().getSimpleName() + " cannot complete inside a caller's transaction. "
                    + "Use a store that can, or use CompletionMode.AUTONOMOUS.");
        }
        if (!transactions.active()) {
            throw new IllegalStateException("Context for " + context.identity()
                    + " asks for CompletionMode.JOIN_TRANSACTION but no transaction is active on this thread. "
                    + "Start the transaction around the engine call, or use CompletionMode.AUTONOMOUS.");
        }
    }

    /**
     * Reports whether this engine's store can complete inside a caller's transaction.
     *
     * <p>Lets an adapter validate a {@link CompletionMode#JOIN_TRANSACTION} operation while it
     * is wiring itself, rather than leaving the caller to discover on the first message that
     * the mode it asked for was never available.
     *
     * @return what the store reports for {@link IdempotencyStore#supportsTransactionalCompletion()}
     */
    public boolean supportsTransactionalCompletion() {
        return store.supportsTransactionalCompletion();
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
        transactions.afterRollback(() -> releaseAfterRollback(context, leaseId));
    }

    /**
     * Frees the key of work a rollback undid and reports the lease's terminal.
     *
     * <p>Runs after the transaction has finished, so the release reaches the store on a
     * connection of the store's own - the same autonomous path the action-failure release takes.
     */
    private void releaseAfterRollback(IdempotencyContext context, String leaseId) {
        IdempotencyRollbackException rollback = new IdempotencyRollbackException(context.identity());
        releaseQuietly(context, leaseId, rollback);
        notifyFailed(context, leaseId, rollback, FailurePhase.ROLLBACK);
    }

    /**
     * Frees the key of a joined completion the store refused, once the transaction rolls back.
     *
     * <p>The failure has already been reported as the lease's terminal, so this fires nothing.
     * Without it the record would sit IN_PROGRESS until its lease expired, turning away every
     * retry of work that, after the rollback, never happened. A caller that swallows the
     * failure and commits anyway gets no release: its work is durable and the record honestly
     * says nothing was recorded.
     */
    private void releaseOnRollback(IdempotencyContext context, String leaseId) {
        transactions.afterRollback(() -> {
            try {
                store.release(context.identity(), leaseId);
            } catch (IdempotencyLeaseLostException leaseLost) {
                // The refusal was a lost lease, so another caller owns the key now: there is
                // nothing of ours to release.
                log.debug("Lease on {} was taken over before its transaction rolled back", context.identity());
            } catch (Exception releaseFailure) {
                log.warn(
                        "Could not release {} after its transaction rolled back; it stays in flight until its lease expires",
                        context.identity(),
                        releaseFailure);
            }
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
