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
import io.github.josipmusa.idempotency.jdbc.ConnectionResolver;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * A {@link ConnectionResolver} that runs {@link Operation#COMPLETE} on the caller's Spring
 * transaction and everything else on a connection of its own.
 *
 * <p>This is the JDBC half of {@link CompletionMode#JOIN_TRANSACTION}. When a transaction is
 * bound to the thread, {@link DataSourceUtils#getConnection(DataSource)} returns the very
 * connection the caller's own writes are going through, so the inbox record joins them and
 * the two commit or roll back as one. With no transaction bound it returns a fresh
 * connection, which is exactly the autonomous behaviour.
 *
 * <p>Every other operation deliberately bypasses the transaction. {@code ACQUIRE} and
 * {@code EXTEND} have to be visible to other callers the moment they run, and {@code RELEASE}
 * runs after the caller's transaction has already rolled back - joining it would either hide
 * the lease or roll the release back with it.
 *
 * <p>{@link #release(Connection)} hands the connection back the same way: through
 * {@link DataSourceUtils#releaseConnection(Connection, DataSource)}, which closes a connection
 * the resolver opened and leaves a transaction-bound one alone.
 */
public class TransactionAwareConnectionResolver implements ConnectionResolver {

    private final DataSource dataSource;

    /**
     * @param dataSource the data source the store's connections come from; must be the same
     *                   one the transaction manager drives, or {@code COMPLETE} would join
     *                   nothing
     */
    public TransactionAwareConnectionResolver(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Connection connectionFor(Operation operation) throws SQLException {
        Objects.requireNonNull(operation, "operation must not be null");
        return operation == Operation.COMPLETE ? DataSourceUtils.getConnection(dataSource) : dataSource.getConnection();
    }

    @Override
    public void release(Connection connection) {
        DataSourceUtils.releaseConnection(connection, dataSource);
    }
}
