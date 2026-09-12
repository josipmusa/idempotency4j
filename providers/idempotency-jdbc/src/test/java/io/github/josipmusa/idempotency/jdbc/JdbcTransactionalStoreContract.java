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

import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.jdbc.ConnectionResolver.Operation;
import io.github.josipmusa.idempotency.test.TransactionalStoreContract;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Runs {@link TransactionalStoreContract} against a {@link JdbcIdempotencyStore} whose
 * {@link ConnectionResolver} hands out one held connection - autocommit off - for
 * {@link Operation#COMPLETE}, and a fresh autocommit connection for everything else.
 *
 * <p>That resolver is the whole of what a transaction manager would otherwise provide, which
 * is the point: the store is unchanged, and only the resolver knows about the transaction.
 */
abstract class JdbcTransactionalStoreContract extends TransactionalStoreContract {

    /**
     * Returns the database to run against.
     *
     * @return the data source
     */
    protected abstract DataSource dataSource();

    private final HeldConnectionResolver resolver = new HeldConnectionResolver();
    private JdbcIdempotencyStore store;

    @Override
    protected IdempotencyStore store() {
        if (store == null) {
            store = new JdbcIdempotencyStore(dataSource(), false, 50, resolver);
        }
        return store;
    }

    @Override
    protected Transaction begin() throws SQLException {
        Connection held = dataSource().getConnection();
        held.setAutoCommit(false);
        resolver.held = held;
        return new HeldTransaction(held, resolver);
    }

    /** Hands out the held transactional connection for {@code COMPLETE} and nothing else. */
    private final class HeldConnectionResolver implements ConnectionResolver {

        private volatile Connection held;

        @Override
        public Connection connectionFor(Operation operation) throws SQLException {
            if (operation == Operation.COMPLETE && held != null) {
                return held;
            }
            return dataSource().getConnection();
        }

        @Override
        public void release(Connection connection) throws SQLException {
            if (connection != held) {
                connection.close();
            }
        }
    }

    /** Commits or rolls back the held connection once, then unbinds and closes it. */
    private static final class HeldTransaction implements Transaction {

        private final Connection held;
        private final HeldConnectionResolver resolver;
        private boolean finished;

        HeldTransaction(Connection held, HeldConnectionResolver resolver) {
            this.held = held;
            this.resolver = resolver;
        }

        @Override
        public void commit() throws SQLException {
            if (!finished) {
                held.commit();
                finish();
            }
        }

        @Override
        public void rollback() throws SQLException {
            if (!finished) {
                held.rollback();
                finish();
            }
        }

        @Override
        public void close() throws SQLException {
            rollback();
        }

        private void finish() throws SQLException {
            finished = true;
            resolver.held = null;
            held.setAutoCommit(true);
            held.close();
        }
    }
}
