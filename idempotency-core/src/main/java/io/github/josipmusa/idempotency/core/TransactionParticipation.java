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
 * The engine's view of the caller's transaction, with no dependency on any transaction API.
 *
 * <p>{@link CompletionMode#JOIN_TRANSACTION} needs two things from whatever manages
 * transactions: to know whether one is running right now, and to be told when it finished.
 * That is the whole interface. A Spring implementation delegates to
 * {@code TransactionSynchronizationManager}; a test implementation records the callbacks and
 * fires them by hand.
 *
 * <p>Implementations must invoke a registered callback exactly once, after the transaction
 * has reached its outcome, and must run {@code afterCommit} only on commit and
 * {@code afterRollback} only on rollback.
 */
public interface TransactionParticipation {

    /**
     * Returns a participation that is never active.
     *
     * <p>The engine's default. An engine holding it cannot run
     * {@link CompletionMode#JOIN_TRANSACTION}: {@link #active()} is always {@code false}, so
     * a context asking for it is rejected with an {@link IllegalStateException}.
     *
     * @return the no-transaction singleton
     */
    static TransactionParticipation none() {
        return NoTransactionParticipation.INSTANCE;
    }

    /**
     * Reports whether the calling thread is inside a transaction the engine could join.
     *
     * @return {@code true} if a transaction is active on this thread
     */
    boolean active();

    /**
     * Registers work to run once the active transaction commits.
     *
     * @param action what to run after the commit
     */
    void afterCommit(Runnable action);

    /**
     * Registers work to run once the active transaction rolls back.
     *
     * @param action what to run after the rollback
     */
    void afterRollback(Runnable action);
}
