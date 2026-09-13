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

/** The {@link TransactionParticipation#none()} singleton. */
final class NoTransactionParticipation implements TransactionParticipation {

    static final NoTransactionParticipation INSTANCE = new NoTransactionParticipation();

    private NoTransactionParticipation() {}

    @Override
    public boolean active() {
        return false;
    }

    @Override
    public void afterCommit(Runnable action) {
        throw new IllegalStateException("No transaction is active; nothing to run after a commit");
    }

    @Override
    public void afterRollback(Runnable action) {
        throw new IllegalStateException("No transaction is active; nothing to run after a rollback");
    }

    @Override
    public String toString() {
        return "TransactionParticipation.none()";
    }
}
