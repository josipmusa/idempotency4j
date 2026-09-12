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
package io.github.josipmusa.idempotency.spring;

import io.github.josipmusa.idempotency.core.CompletionMode;
import io.github.josipmusa.idempotency.core.TransactionParticipation;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The engine's view of a Spring-managed transaction, read from
 * {@link TransactionSynchronizationManager}.
 *
 * <p>Hand one to {@link io.github.josipmusa.idempotency.core.IdempotencyEngine} to let a
 * context ask for {@link CompletionMode#JOIN_TRANSACTION}: the inbox record is then written
 * inside whatever transaction {@code @Transactional} already opened, and the two commit
 * together.
 *
 * <p>The instance is stateless and thread-safe - all state it reads is the thread-bound
 * state Spring's transaction infrastructure maintains.
 */
public class SpringTransactionParticipation implements TransactionParticipation {

    /**
     * Reports whether a real transaction - not merely synchronization - is bound to this
     * thread.
     *
     * <p>Both halves matter. Without an actual transaction there is nothing to commit the
     * record with, and without synchronization there is no way to be told when it finished.
     *
     * @return {@code true} when the engine can join the caller's transaction
     */
    @Override
    public boolean active() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }

    @Override
    public void afterCommit(Runnable action) {
        register(Objects.requireNonNull(action, "action must not be null"), true);
    }

    @Override
    public void afterRollback(Runnable action) {
        register(Objects.requireNonNull(action, "action must not be null"), false);
    }

    /**
     * Registers a callback that runs on {@code afterCompletion} for the matching outcome.
     *
     * <p>{@code afterCompletion} rather than {@code afterCommit}, because it is the one hook
     * Spring guarantees to call whichever way the transaction ended - the engine needs both
     * outcomes, and needs exactly one of them.
     *
     * <p>Only {@link TransactionSynchronization#STATUS_COMMITTED} counts as a commit;
     * everything else, {@link TransactionSynchronization#STATUS_UNKNOWN} included, is treated
     * as a rollback. {@code STATUS_UNKNOWN} means the transaction manager could not determine
     * the outcome - a heuristic JTA completion, or an earlier synchronization throwing during
     * the commit. Matching it against neither branch would leave the engine's terminal
     * callback unfired and the lease neither completed nor released, breaking the
     * one-terminal-per-lease invariant that
     * {@link io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener} promises. An
     * indeterminate outcome is not a confirmed commit, and the record must not be announced
     * as durable on the strength of one, so it is resolved the safe way: release the lease
     * and report the failure, which costs at most a re-execution.
     */
    private static void register(Runnable action, boolean onCommit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Cannot register a transaction callback: no transaction is active on this thread");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                boolean committed = status == TransactionSynchronization.STATUS_COMMITTED;
                if (committed == onCommit) {
                    action.run();
                }
            }
        });
    }
}
