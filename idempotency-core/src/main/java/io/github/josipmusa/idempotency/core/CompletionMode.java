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
 * How the engine records the completion relative to the caller's own transaction.
 *
 * <p>Set on {@link IdempotencyContext}, because it is a property of the operation rather
 * than of the engine: an HTTP request and a message listener can share one engine and still
 * want different answers.
 */
public enum CompletionMode {

    /**
     * The completion is recorded on its own, independently of anything the action touched.
     *
     * <p>The record becomes COMPLETE the moment {@link IdempotencyStore#complete} returns.
     * This is the default and the only mode a store that cannot enlist in a caller's
     * transaction supports. It leaves a window: if the process dies between the action's own
     * commit and this one, the record stays IN_PROGRESS and a redelivery runs the action
     * again.
     */
    AUTONOMOUS,

    /**
     * The completion is recorded inside the transaction the action is already running in, so
     * the record and the action's writes commit or roll back together.
     *
     * <p>This closes the window {@link #AUTONOMOUS} leaves: a crash before the commit leaves
     * neither the business writes nor a completed record, and a crash after it leaves both.
     * It requires an active transaction at
     * {@link IdempotencyEngine#execute(IdempotencyContext, ThrowingSupplier, PayloadCodec)}
     * entry and a store whose
     * {@link IdempotencyStore#supportsTransactionalCompletion()} is {@code true}.
     *
     * <p>{@link IdempotencyLifecycleListener#onCompleted} is deferred until the transaction
     * actually commits. A rollback releases the lease and fires
     * {@link IdempotencyLifecycleListener.FailurePhase#ROLLBACK} instead.
     */
    JOIN_TRANSACTION
}
