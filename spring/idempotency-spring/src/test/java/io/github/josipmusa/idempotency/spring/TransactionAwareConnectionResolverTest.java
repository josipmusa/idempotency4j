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

import static io.github.josipmusa.idempotency.jdbc.ConnectionResolver.Operation.COMPLETE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.josipmusa.idempotency.jdbc.ConnectionResolver.Operation;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TransactionAwareConnectionResolverTest {

    @Test
    void When_NoTransactionActive_Expect_FreshConnectionForEveryOperation() throws SQLException {
        Connection connection = mock(Connection.class);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        TransactionAwareConnectionResolver resolver = new TransactionAwareConnectionResolver(dataSource);

        Connection resolved = resolver.connectionFor(COMPLETE);
        resolver.release(resolved);

        assertThat(resolved).isSameAs(connection);
        verify(connection).close();
    }

    @Test
    void When_OperationIsComplete_Expect_TransactionBoundConnectionLeftOpen() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        TransactionAwareConnectionResolver resolver = new TransactionAwareConnectionResolver(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transactions.executeWithoutResult(status -> {
            try {
                Connection resolved = resolver.connectionFor(COMPLETE);
                assertThat(resolved).isSameAs(connection);
                resolver.release(resolved);
                verify(connection, never()).close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        verify(connection).commit();
    }

    @Test
    void When_OperationIsNotComplete_Expect_ConnectionOutsideTheTransaction() throws SQLException {
        Connection transactional = mock(Connection.class);
        when(transactional.getAutoCommit()).thenReturn(true);
        Connection fresh = mock(Connection.class);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(transactional, fresh);
        TransactionAwareConnectionResolver resolver = new TransactionAwareConnectionResolver(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transactions.executeWithoutResult(status -> {
            for (Operation operation : new Operation[] {Operation.ACQUIRE, Operation.RELEASE, Operation.EXTEND}) {
                try {
                    Connection resolved = resolver.connectionFor(operation);
                    assertThat(resolved).isSameAs(fresh);
                    resolver.release(resolved);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        verify(fresh, never()).setAutoCommit(anyBoolean());
        verify(fresh, times(3)).close();
    }
}
