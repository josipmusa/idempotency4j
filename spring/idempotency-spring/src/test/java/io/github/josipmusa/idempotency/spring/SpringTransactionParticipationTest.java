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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SpringTransactionParticipationTest {

    private final SpringTransactionParticipation participation = new SpringTransactionParticipation();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void When_NoTransactionOnThread_Expect_NotActive() {
        assertThat(participation.active()).isFalse();
    }

    @Test
    void When_TransactionActiveOnThread_Expect_Active() {
        beginTransaction();

        assertThat(participation.active()).isTrue();
    }

    @Test
    void When_SynchronizationActiveButNoRealTransaction_Expect_NotActive() {
        TransactionSynchronizationManager.initSynchronization();

        assertThat(participation.active()).isFalse();
    }

    @Test
    void When_TransactionCommits_Expect_AfterCommitActionRun() {
        beginTransaction();
        List<String> events = new ArrayList<>();
        participation.afterCommit(() -> events.add("committed"));
        participation.afterRollback(() -> events.add("rolled-back"));

        fireCompletion(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(events).containsExactly("committed");
    }

    @Test
    void When_TransactionRollsBack_Expect_AfterRollbackActionRun() {
        beginTransaction();
        List<String> events = new ArrayList<>();
        participation.afterCommit(() -> events.add("committed"));
        participation.afterRollback(() -> events.add("rolled-back"));

        fireCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(events).containsExactly("rolled-back");
    }

    @Test
    void When_RegisteringWithoutTransaction_Expect_Rejected() {
        assertThatThrownBy(() -> participation.afterCommit(() -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no transaction");
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void fireCompletion(int status) {
        List<TransactionSynchronization> synchronizations =
                List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        synchronizations.forEach(s -> s.afterCompletion(status));
    }
}
