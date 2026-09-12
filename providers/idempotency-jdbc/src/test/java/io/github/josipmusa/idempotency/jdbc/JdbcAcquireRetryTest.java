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
package io.github.josipmusa.idempotency.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Acquire-path classification of transient database failures. No container: the point is what
 * the store does with a given {@link java.sql.SQLException}, not what a real server produces.
 */
class JdbcAcquireRetryTest {

    private static final String SCOPE = "ContractScope.action";

    @Test
    void When_InsertDeadlocks_Expect_InFlightNotStoreFailure() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(conn.getMetaData()).thenReturn(metaData);

        ResultSet clock = mock(ResultSet.class);
        when(clock.next()).thenReturn(true);
        when(clock.getTimestamp(eq(1), any(Calendar.class))).thenReturn(Timestamp.from(Instant.now()));
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenReturn(clock);
        when(conn.createStatement()).thenReturn(statement);

        // The row is always gone when we look, and every insert loses the race with a deleter.
        PreparedStatement insert = mock(PreparedStatement.class);
        when(insert.executeUpdate())
                .thenThrow(new SQLTransactionRollbackException(
                        "Deadlock found when trying to get lock; try restarting transaction", "40001", 1213));
        ResultSet noRow = mock(ResultSet.class);
        when(noRow.next()).thenReturn(false);
        PreparedStatement selectForUpdate = mock(PreparedStatement.class);
        when(selectForUpdate.executeQuery()).thenReturn(noRow);
        PreparedStatement other = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("INSERT")) {
                return insert;
            }
            return sql.contains("FOR UPDATE") ? selectForUpdate : other;
        });

        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(conn);
        JdbcIdempotencyStore store = new JdbcIdempotencyStore(dataSource, false);

        IdempotencyContext context = IdempotencyContext.builder(SCOPE, "deadlocked-key")
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofSeconds(5))
                .waitTimeout(Duration.ZERO)
                .build();

        assertThat(store.tryAcquire(context))
                .as("a deadlock is a lost race the poll loop already knows how to handle, not a broken store")
                .isInstanceOf(AcquireResult.InFlight.class);
    }
}
