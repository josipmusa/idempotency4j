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

/**
 * Observes the idempotency lifecycle as {@link IdempotencyEngine} drives it.
 *
 * <p>Register one or more listeners with the engine to correlate other work with
 * the idempotent boundary: bind the key to the request thread, tag rows written
 * while the action runs, or resubmit work when a duplicate arrives. Listeners
 * observe only - they must not touch store state, and nothing they do changes
 * what the engine returns or throws.
 *
 * <h2>Dispatch</h2>
 * <p>Callbacks run <strong>synchronously, on the thread calling the engine</strong>,
 * in registration order. This is intentional: consumers rely on it to bind
 * thread-local state that the guarded action, running on that same thread, then
 * observes. A listener that blocks blocks the request.
 *
 * <p>Exceptions thrown by a listener are logged at WARN and swallowed. They do
 * not abort the remaining listeners, the action, the stored payload, or the
 * exception the engine is already propagating. {@link Error}s are not swallowed.
 *
 * <h2>Callback invariants</h2>
 * <ul>
 *   <li>Every acquired lease gets <strong>exactly one</strong> terminal callback:
 *       {@link #onCompleted} or {@link #onFailed}, never both and never neither.
 *       {@link #onAcquired} always precedes it, with the same {@code leaseId}.
 *       Consumers use this pair to unbind per-request state, so the engine treats
 *       it as load-bearing.</li>
 *   <li>{@link #onDuplicate} stands alone. No lease was acquired, so no terminal
 *       callback follows and {@code onAcquired} did not precede it.</li>
 *   <li>{@link #onCompleted} fires only after the store confirmed completion. An
 *       {@link io.github.josipmusa.idempotency.core.exception.IdempotencyDurabilityException}
 *       is a failure, not a completion: it fires
 *       {@link #onFailed} with {@link FailurePhase#COMPLETION}.</li>
 *   <li>A lock timeout or a fingerprint mismatch acquires no lease and fires
 *       nothing - the engine throws and the action never runs.</li>
 *   <li>{@code onAcquired} means the action is about to run. In the one case where
 *       the engine acquires a lease and abandons it before that (it could not start
 *       the heartbeat), it releases the lease and fires nothing at all, rather than
 *       announcing a lease no terminal callback would close.</li>
 *   <li>Heartbeat activity ({@link IdempotencyStore#extendLock}) is not surfaced.</li>
 * </ul>
 *
 * <p>Every method has an empty default, so an implementation overrides only the
 * callbacks it cares about.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * class KeyBindingListener implements IdempotencyLifecycleListener {
 *
 *     private static final ThreadLocal<String> CURRENT_KEY = new ThreadLocal<>();
 *
 *     @Override
 *     public void onAcquired(IdempotencyContext ctx, String leaseId) {
 *         CURRENT_KEY.set(ctx.key());
 *     }
 *
 *     @Override
 *     public void onCompleted(IdempotencyContext ctx, String leaseId, IdempotencyPayload payload) {
 *         CURRENT_KEY.remove();
 *     }
 *
 *     @Override
 *     public void onFailed(IdempotencyContext ctx, String leaseId, Throwable cause, FailurePhase phase) {
 *         CURRENT_KEY.remove();
 *     }
 * }
 * }</pre>
 */
public interface IdempotencyLifecycleListener {

    /**
     * The lock was acquired and the action is about to run on this thread.
     *
     * <p>Fires before the action, once per acquired lease, and is always followed
     * by exactly one of {@link #onCompleted} or {@link #onFailed} carrying the
     * same {@code leaseId}.
     *
     * @param ctx     the context this execution is running under
     * @param leaseId the ownership lease for this acquisition
     */
    default void onAcquired(IdempotencyContext ctx, String leaseId) {}

    /**
     * The store recorded the completion; the idempotent boundary closed cleanly.
     *
     * <p>Fires from {@link IdempotencyEngine#complete}, after the store confirmed
     * the transition to COMPLETE. Nothing more will happen under this lease.
     *
     * @param ctx     the context this execution ran under
     * @param leaseId the lease that was completed
     * @param payload what was stored for a duplicate to replay - a
     *                {@link StoredResponse} for HTTP, {@link NoPayload} otherwise
     */
    default void onCompleted(IdempotencyContext ctx, String leaseId, IdempotencyPayload payload) {}

    /**
     * The execution ended without a recorded completion.
     *
     * <p>The {@code phase} says what a retry can expect - see
     * {@link FailurePhase}. Nothing more will happen under this lease.
     *
     * @param ctx     the context this execution ran under
     * @param leaseId the lease that failed
     * @param cause   the exception the engine is propagating; a failure to release
     *                the lease, if any, is attached to it as a suppressed exception
     * @param phase   where the failure happened
     */
    default void onFailed(IdempotencyContext ctx, String leaseId, Throwable cause, FailurePhase phase) {}

    /**
     * The key was already completed, so the action was skipped.
     *
     * <p>Fires instead of the {@code onAcquired}/terminal pair: no lease exists
     * for a duplicate, and nothing follows this callback.
     *
     * @param ctx     the context the duplicate arrived under
     * @param payload what the original execution stored - a {@link StoredResponse}
     *                for HTTP, or {@link NoPayload} when there is nothing to replay
     */
    default void onDuplicate(IdempotencyContext ctx, IdempotencyPayload payload) {}

    /** Where an execution failed, and therefore what a retry under the same key will do. */
    enum FailurePhase {

        /**
         * The guarded action threw. The lease was released, so the key is
         * immediately reclaimable and a retry will execute the action again.
         */
        ACTION,

        /**
         * The action succeeded but the store could not record the completion.
         * Storage state is indeterminate - the mutation may or may not have
         * landed - so a retry will most likely execute the action again even
         * though its side effects already happened.
         */
        COMPLETION
    }
}
