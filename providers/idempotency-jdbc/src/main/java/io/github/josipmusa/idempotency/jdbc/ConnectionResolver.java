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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Decides which {@link Connection} each store operation runs on.
 *
 * <p>This is the seam that lets the inbox row commit with the caller's own writes. The store
 * itself has no opinion about transactions: it asks for a connection, runs its statement, and
 * hands the connection back. <strong>The resolver decides, the store obeys.</strong>
 *
 * <p>Only {@link Operation#COMPLETE} is expected to ever return a transaction-bound
 * connection. Everything else must run autonomously, because it either has to be visible to
 * other callers immediately ({@code ACQUIRE}, {@code EXTEND}) or runs after the caller's
 * transaction is already finished ({@code RELEASE} after a rollback).
 *
 * <p>A store must never close or commit a connection it did not open, so it never calls
 * {@link Connection#close()} directly - it calls {@link #release(Connection)} and lets the
 * resolver decide. The default implementation closes, which is right for the connections
 * {@link #forDataSource(DataSource)} hands out; a transaction-aware resolver overrides it to
 * leave a transaction-bound connection alone.
 */
public interface ConnectionResolver {

    /**
     * Returns a resolver that opens a fresh autocommit connection for every operation.
     *
     * <p>The default, and the only behaviour available without a transaction manager. A store
     * using it records completions autonomously.
     *
     * @param dataSource where the connections come from
     * @return a resolver backed by {@code dataSource}
     */
    static ConnectionResolver forDataSource(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        return operation -> dataSource.getConnection();
    }

    /**
     * Returns the connection the given operation should run on.
     *
     * @param operation what the store is about to do
     * @return the connection to use; never {@code null}
     * @throws SQLException if no connection could be obtained
     */
    Connection connectionFor(Operation operation) throws SQLException;

    /**
     * Hands a connection back once the store is done with it.
     *
     * <p>Called in a {@code finally} block for every connection
     * {@link #connectionFor(Operation)} returned. The default closes it. An implementation
     * that hands out a connection it does not own - a transaction-bound one, above all - must
     * override this to leave that connection open, because closing it would end the caller's
     * transaction.
     *
     * @param connection the connection to hand back
     * @throws SQLException if the connection could not be released
     */
    default void release(Connection connection) throws SQLException {
        connection.close();
    }

    /** What the store is about to do, so a resolver can answer differently per operation. */
    enum Operation {

        /** Acquiring or polling for the lease. Must be autonomous. */
        ACQUIRE,

        /**
         * Recording the completion. The one operation that may run on the caller's
         * transaction, which is what makes the record commit with the caller's own writes.
         */
        COMPLETE,

        /** Deleting the record after a failure or a rollback. Must be autonomous. */
        RELEASE,

        /** Extending the lease from the heartbeat. Must be autonomous. */
        EXTEND,

        /** Deleting expired records. Must be autonomous. */
        PURGE
    }
}
