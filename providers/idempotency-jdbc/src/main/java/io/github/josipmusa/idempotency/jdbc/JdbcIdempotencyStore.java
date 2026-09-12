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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyIdentity;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.Payload;
import io.github.josipmusa.idempotency.core.exception.IdempotencyCorruptRecordException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreUnavailableException;
import io.github.josipmusa.idempotency.jdbc.ConnectionResolver.Operation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * JDBC implementation of {@link IdempotencyStore}.
 *
 * <p>Uses plain JDBC with {@code SELECT ... FOR UPDATE} for safe lock
 * stealing and blocking. Compatible with any database that supports
 * row-level locking (MySQL, PostgreSQL, etc.).
 *
 * <p>No Spring dependencies — only requires a {@link DataSource}.
 *
 * <p><strong>Database compatibility:</strong> Automatically detects the database
 * dialect from {@link java.sql.DatabaseMetaData#getDatabaseProductName()} and
 * loads the appropriate schema file ({@code idempotency-schema-mysql.sql} or
 * {@code idempotency-schema-postgresql.sql}). Unrecognized databases fall back
 * to the MySQL schema.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final long DEFAULT_POLL_INTERVAL_MS = 100;

    /**
     * Reads the database clock as UTC wall-clock fields, so {@link #currentTime} can turn it
     * into a true {@link Instant} with {@link #UTC}. A plain {@code CURRENT_TIMESTAMP} returns
     * the server's local time, which a driver is free to interpret in its own zone: on MySQL
     * that silently shifts the value by the JVM's offset, which is harmless for arithmetic
     * against other columns written the same way but wrong for {@code completed_at}, which
     * leaves the store as an instant a caller sees.
     */
    private static final String SELECT_CURRENT_TIME_MYSQL = "SELECT UTC_TIMESTAMP(3)";

    private static final String SELECT_CURRENT_TIME_POSTGRESQL = "SELECT CURRENT_TIMESTAMP(3) AT TIME ZONE 'UTC'";

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static final String DELETE_EXPIRED = "DELETE FROM idempotency_records "
            + "WHERE scope = ? AND idempotency_key = ? AND expires_at < ? AND status = 'COMPLETE'";

    private static final String INSERT = "INSERT INTO idempotency_records "
            + "(scope, idempotency_key, status, lease_expires_at, expires_at, fingerprint, lease_id) "
            + "VALUES (?, ?, 'IN_PROGRESS', ?, ?, ?, ?)";

    private static final String SELECT_FOR_UPDATE =
            "SELECT status, lease_expires_at, payload_type, payload, attributes, completed_at, fingerprint "
                    + "FROM idempotency_records WHERE scope = ? AND idempotency_key = ? FOR UPDATE";

    private static final String SELECT_STATUS_AND_LEASE =
            "SELECT status, lease_id FROM idempotency_records WHERE scope = ? AND idempotency_key = ?";

    private static final String STEAL_LEASE =
            "UPDATE idempotency_records SET status = 'IN_PROGRESS', lease_expires_at = ?, "
                    + "fingerprint = ?, lease_id = ?, "
                    + "payload_type = NULL, payload = NULL, attributes = NULL, completed_at = NULL "
                    + "WHERE scope = ? AND idempotency_key = ? "
                    + "AND status = 'IN_PROGRESS' AND lease_expires_at < ?";

    private static final String COMPLETE =
            "UPDATE idempotency_records SET status = 'COMPLETE', payload_type = ?, payload = ?, "
                    + "attributes = ?, completed_at = ?, lease_expires_at = NULL, lease_id = NULL, expires_at = ? "
                    + "WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND lease_id = ?";

    private static final String RELEASE = "DELETE FROM idempotency_records "
            + "WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND lease_id = ?";

    private static final String EXTEND_LEASE = "UPDATE idempotency_records SET lease_expires_at = ? "
            + "WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND lease_id = ?";

    private static final String PURGE_EXPIRED = "DELETE FROM idempotency_records WHERE expires_at < ? "
            + "AND (status <> 'IN_PROGRESS' OR lease_expires_at < ?)";

    /**
     * Outcome of inspecting a locked row during the poll loop.
     *
     * <ul>
     *   <li>{@code result} non-null — outcome is fully resolved (COMPLETE or lease stolen)</li>
     *   <li>{@code rowGone} true — row disappeared; retry insert immediately, no sleep</li>
     *   <li>both null/false — row is active IN_PROGRESS; sleep and poll again.
     *       {@code remainingLease} is how long the holder's lease still has to run, which
     *       becomes {@code InFlight.retryAfter} if the wait budget runs out.</li>
     * </ul>
     */
    private record RowInspection(AcquireResult result, boolean rowGone, Duration remainingLease) {
        static RowInspection resolved(AcquireResult r) {
            return new RowInspection(r, false, Duration.ZERO);
        }

        static RowInspection gone() {
            return new RowInspection(null, true, Duration.ZERO);
        }

        static RowInspection keepPolling(Duration remainingLease) {
            return new RowInspection(null, false, remainingLease);
        }
    }

    private final DataSource dataSource;
    private final ConnectionResolver connections;
    private final long pollIntervalMs;

    /**
     * The dialect's clock query, resolved from the first connection this store uses rather
     * than in the constructor, so a store built against an unreachable {@code DataSource}
     * still constructs and fails where the caller can see it.
     */
    private volatile String currentTimeSql;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> ATTRIBUTES_TYPE = new TypeReference<>() {};

    public JdbcIdempotencyStore(DataSource dataSource) {
        this(dataSource, true);
    }

    public JdbcIdempotencyStore(DataSource dataSource, boolean initSchema) {
        this(dataSource, initSchema, DEFAULT_POLL_INTERVAL_MS);
    }

    public JdbcIdempotencyStore(DataSource dataSource, boolean initSchema, long pollIntervalMs) {
        this(dataSource, initSchema, pollIntervalMs, null);
    }

    /**
     * Builds a store with the default poll interval whose connections come from the given
     * resolver.
     *
     * @param dataSource  the database, used for schema initialisation and as the default source
     *                    of connections
     * @param initSchema  whether to create the table and index on construction
     * @param connections decides which connection each operation runs on; {@code null} means
     *                    {@link ConnectionResolver#forDataSource(DataSource)}
     */
    public JdbcIdempotencyStore(DataSource dataSource, boolean initSchema, ConnectionResolver connections) {
        this(dataSource, initSchema, DEFAULT_POLL_INTERVAL_MS, connections);
    }

    /**
     * Builds a store whose connections come from the given resolver.
     *
     * <p>The {@code DataSource} is still required: schema initialisation uses it directly, and
     * it is what the default resolver is built from when {@code connections} is {@code null}.
     *
     * @param dataSource     the database, used for schema initialisation and as the default
     *                       source of connections
     * @param initSchema     whether to create the table and index on construction
     * @param pollIntervalMs how long {@code tryAcquire} sleeps between polls, in milliseconds
     * @param connections    decides which connection each operation runs on; {@code null}
     *                       means {@link ConnectionResolver#forDataSource(DataSource)}
     */
    public JdbcIdempotencyStore(
            DataSource dataSource, boolean initSchema, long pollIntervalMs, ConnectionResolver connections) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.connections = connections != null ? connections : ConnectionResolver.forDataSource(dataSource);
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive, got: " + pollIntervalMs);
        }
        this.pollIntervalMs = pollIntervalMs;
        if (initSchema) {
            initSchema();
        }
    }

    /**
     * Reports {@code true}: a JDBC store completes on whatever connection its
     * {@link ConnectionResolver} hands it, so a resolver that returns the caller's
     * transaction-bound connection for {@link Operation#COMPLETE} makes the record commit with
     * the caller's own writes.
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsTransactionalCompletion() {
        return true;
    }

    /**
     * Runs {@code work} on a connection resolved for {@code operation} and hands it back
     * afterwards, whatever happens.
     *
     * <p>The connection is returned through {@link ConnectionResolver#release(Connection)}
     * rather than closed, because the store does not know whether it owns it.
     */
    private <T> T using(Operation operation, SqlWork<T> work) throws SQLException {
        Connection conn = connections.connectionFor(operation);
        try {
            return work.run(conn);
        } finally {
            releaseConnection(conn);
        }
    }

    private void releaseConnection(Connection conn) {
        try {
            connections.release(conn);
        } catch (SQLException ignored) {
            // Handing the connection back on the way out, possibly while a failure is already
            // being reported. A broken connection is discarded by the pool anyway, and masking
            // the caller's real failure with this one would only lose information.
        }
    }

    /** A unit of work that needs a connection; see {@link #using}. */
    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection conn) throws SQLException;
    }

    /**
     * Executes the dialect-specific schema DDL. Detects MySQL vs PostgreSQL from
     * {@link java.sql.DatabaseMetaData#getDatabaseProductName()}.
     *
     * <p>For MySQL, the index is created separately with duplicate-key error handling
     * since MySQL does not support {@code CREATE INDEX IF NOT EXISTS}.
     */
    private void initSchema() {
        String dialect;
        try (Connection conn = dataSource.getConnection()) {
            dialect = conn.getMetaData().getDatabaseProductName().toLowerCase();
        } catch (SQLException e) {
            throw new IdempotencyStoreUnavailableException("Failed to detect database dialect", e);
        }

        String schemaFile;
        if (dialect.contains("postgresql")) {
            schemaFile = "/idempotency-schema-postgresql.sql";
        } else {
            schemaFile = "/idempotency-schema-mysql.sql";
        }

        try (InputStream is = getClass().getResourceAsStream(schemaFile)) {
            if (is == null) {
                throw new IdempotencyStoreException("Schema file " + schemaFile + " not found on classpath");
            }
            String sql;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                sql = sb.toString().trim();
            }
            try (Connection conn = dataSource.getConnection();
                    Statement stmt = conn.createStatement()) {
                String[] statements = sql.split(";");
                for (String statement : statements) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
            }
        } catch (IOException | SQLException e) {
            throw new IdempotencyStoreUnavailableException("Failed to initialize schema", e);
        }

        if (!dialect.contains("postgresql")) {
            createMysqlIndex();
        }
    }

    private void createMysqlIndex() {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE INDEX idx_idempotency_expires ON idempotency_records (expires_at)");
        } catch (SQLException e) {
            // MySQL error 1061 = duplicate key name (index already exists)
            if (e.getErrorCode() != 1061) {
                throw new IdempotencyStoreUnavailableException("Failed to create index", e);
            }
        }
    }

    @Override
    public AcquireResult tryAcquire(IdempotencyContext context) {
        long startedAtNanos = System.nanoTime();
        long waitNanos = context.waitTimeout().toNanos();
        String leaseId = UUID.randomUUID().toString();

        // Fast path: evict any expired COMPLETE record for this identity, then insert a fresh
        // IN_PROGRESS row. The DELETE and INSERT run as separate autocommit statements —
        // no explicit transaction. If a concurrent caller inserts between our DELETE and
        // INSERT, the INSERT throws a duplicate-key violation. The poll loop below handles
        // that correctly, so the lack of an explicit transaction here is intentional.
        if (tryInsert(context, leaseId)) {
            return AcquireResult.acquired(leaseId);
        }

        // Duplicate key — poll until we can acquire, the operation completes, or the wait
        // budget runs out. A zero wait still takes one look: the loop body runs at least once.
        boolean firstAttempt = true;
        Duration remainingLease = Duration.ZERO;
        while (firstAttempt || System.nanoTime() - startedAtNanos < waitNanos) {
            firstAttempt = false;
            RowInspection inspection = inspectRow(context, leaseId);

            if (inspection.result() != null) {
                return inspection.result();
            }

            if (inspection.rowGone()) {
                // Row disappeared between our INSERT attempt and the SELECT FOR UPDATE.
                // Retry the insert immediately — no sleep needed, the slot is free.
                if (tryInsert(context, leaseId)) return AcquireResult.acquired(leaseId);
                continue; // someone else inserted first, loop back to poll
            }

            // Active IN_PROGRESS — sleep before next poll
            remainingLease = inspection.remainingLease();
            try {
                long remainingNanos = waitNanos - (System.nanoTime() - startedAtNanos);
                if (remainingNanos <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(pollIntervalMs)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AcquireResult.inFlight(remainingLease);
            }
        }

        return AcquireResult.inFlight(remainingLease);
    }

    @Override
    public void complete(IdempotencyIdentity identity, String leaseId, Payload payload, Duration ttl) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        requirePositiveDuration(ttl, "ttl");
        try {
            using(Operation.COMPLETE, conn -> {
                Instant now = currentTime(conn);
                try (PreparedStatement ps = conn.prepareStatement(COMPLETE)) {
                    bindPayload(ps, payload);
                    setTimestamp(ps, 4, now);
                    setTimestamp(ps, 5, now.plus(ttl));
                    bindIdentity(ps, 6, identity);
                    ps.setString(8, leaseId);
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        throw diagnoseMissingInProgress(conn, identity, leaseId, "complete");
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            throw unavailable("complete " + identity, e);
        }
    }

    /**
     * Binds the payload columns of {@link #COMPLETE}.
     *
     * <p>Every payload has a type, so {@code payload_type} is always written; it is what
     * {@link #readPayload} keys off to tell a stored payload from an empty column set.
     */
    private static void bindPayload(PreparedStatement ps, Payload payload) throws SQLException {
        ps.setString(1, payload.type());
        ps.setBytes(2, payload.body());
        ps.setString(3, attributesToJson(payload.attributes()));
    }

    @Override
    public void release(IdempotencyIdentity identity, String leaseId) {
        Objects.requireNonNull(identity, "identity must not be null");
        try {
            using(Operation.RELEASE, conn -> {
                conn.setAutoCommit(false);
                deleteInTransaction(conn, identity, leaseId);
                return null;
            });
        } catch (SQLException e) {
            throw unavailable("release " + identity, e);
        }
    }

    /**
     * Deletes the row outright: a failed attempt leaves no trace, and the next
     * {@code tryAcquire} for this identity inserts a fresh row.
     */
    private void deleteInTransaction(Connection conn, IdempotencyIdentity identity, String leaseId) {
        try {
            try (PreparedStatement ps = conn.prepareStatement(RELEASE)) {
                bindIdentity(ps, 1, identity);
                ps.setString(3, leaseId);
                if (ps.executeUpdate() == 0) {
                    throw diagnoseMissingInProgress(conn, identity, leaseId, "release");
                }
            }
            conn.commit();
        } catch (IdempotencyStoreException e) {
            rollbackQuietly(conn);
            throw e;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw unavailable("release " + identity, e);
        } finally {
            resetAutoCommit(conn);
        }
    }

    @Override
    public void extendLock(IdempotencyIdentity identity, String leaseId, Duration extension) {
        Objects.requireNonNull(identity, "identity must not be null");
        requirePositiveDuration(extension, "extension");
        try {
            using(Operation.EXTEND, conn -> {
                Instant newExpiry = currentTime(conn).plus(extension);
                try (PreparedStatement ps = conn.prepareStatement(EXTEND_LEASE)) {
                    setTimestamp(ps, 1, newExpiry);
                    bindIdentity(ps, 2, identity);
                    ps.setString(4, leaseId);
                    ps.executeUpdate();
                    // Silently ignore if no rows updated - heartbeat may fire after completion.
                }
                return null;
            });
        } catch (SQLException e) {
            throw unavailable("extend lock for " + identity, e);
        }
    }

    /**
     * Deletes stale records from the database in a single query.
     *
     * <p>Removes every row whose {@code expires_at} is in the past and that nobody
     * owns: an IN_PROGRESS row also needs an expired {@code lease_expires_at}. A row
     * whose lease is still being heartbeated is never deleted, however old it is.
     * An expired lease on its own is not a reason to delete a row either: that row
     * is stealable by the next {@code tryAcquire} caller.
     *
     * <p>{@code idx_idempotency_expires} drives the scan; the ownership clause is a
     * filter on the rows it returns.
     *
     * @return the number of rows deleted
     */
    @Override
    public int purgeExpired() {
        try {
            return using(Operation.PURGE, conn -> {
                Instant now = currentTime(conn);
                try (PreparedStatement ps = conn.prepareStatement(PURGE_EXPIRED)) {
                    setTimestamp(ps, 1, now);
                    setTimestamp(ps, 2, now);
                    return ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            throw unavailable("purge expired records", e);
        }
    }

    /**
     * Evicts an expired COMPLETE record for this identity, then attempts an INSERT of a fresh
     * IN_PROGRESS row.
     *
     * @return {@code true} if the row was inserted (lock acquired), {@code false} if a row
     *     already exists (duplicate key)
     */
    private boolean tryInsert(IdempotencyContext context, String leaseId) {
        IdempotencyIdentity identity = context.identity();
        try {
            return using(Operation.ACQUIRE, conn -> {
                Instant now = currentTime(conn);
                try (PreparedStatement del = conn.prepareStatement(DELETE_EXPIRED)) {
                    bindIdentity(del, 1, identity);
                    setTimestamp(del, 3, now);
                    del.executeUpdate();
                }
                return insertRow(conn, context, leaseId, now);
            });
        } catch (SQLException e) {
            if (isTransientRollback(e)) {
                return false;
            }
            throw unavailable("perform initial acquire for " + identity, e);
        }
    }

    /**
     * Inserts the fresh IN_PROGRESS row.
     *
     * <p>A failure here is not automatically a broken store. A duplicate key means another
     * caller inserted first, and a transient rollback means this insert lost a race against a
     * concurrent delete of the same primary key. Both are a lost race, reported as
     * {@code false} so the poll loop re-inspects the row within the caller's wait timeout.
     *
     * @return {@code true} if the row was inserted, {@code false} if the race was lost
     */
    private boolean insertRow(Connection conn, IdempotencyContext context, String leaseId, Instant now) {
        try (PreparedStatement ins = conn.prepareStatement(INSERT)) {
            bindIdentity(ins, 1, context.identity());
            setTimestamp(ins, 3, now.plus(context.leaseDuration()));
            setTimestamp(ins, 4, now.plus(context.ttl()));
            ins.setString(5, context.requestFingerprint());
            ins.setString(6, leaseId);
            ins.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (!isDuplicateKeyViolation(e) && !isTransientRollback(e)) {
                throw unavailable("insert record for " + context.identity(), e);
            }
            return false;
        }
    }

    /**
     * Opens a {@code SELECT FOR UPDATE} transaction to inspect the current row state and
     * decide what to do next.
     */
    private RowInspection inspectRow(IdempotencyContext context, String leaseId) {
        try {
            return using(Operation.ACQUIRE, conn -> {
                conn.setAutoCommit(false);
                return inspectRowInTransaction(conn, context, leaseId);
            });
        } catch (SQLException e) {
            throw unavailable("get connection for " + context.identity(), e);
        }
    }

    /**
     * Runs the inspection and commits it, translating a failure of the transaction itself. A
     * transient rollback (a deadlock or lock-wait timeout against a concurrent writer) is a
     * lost race rather than a broken store, so the caller simply polls again.
     */
    private RowInspection inspectRowInTransaction(Connection conn, IdempotencyContext context, String leaseId) {
        try {
            RowInspection inspection = doInspectRow(conn, context, leaseId);
            conn.commit();
            return inspection;
        } catch (IdempotencyStoreException e) {
            rollbackQuietly(conn);
            throw e;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            if (isTransientRollback(e)) {
                return RowInspection.keepPolling(Duration.ZERO);
            }
            throw unavailable("poll " + context.identity(), e);
        } finally {
            resetAutoCommit(conn);
        }
    }

    private RowInspection doInspectRow(Connection conn, IdempotencyContext context, String leaseId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_FOR_UPDATE)) {
            bindIdentity(ps, 1, context.identity());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return RowInspection.gone();
                }

                String status = rs.getString("status");
                Timestamp leaseExpiresTs = getTimestamp(rs, "lease_expires_at");

                if ("COMPLETE".equals(status)) {
                    String storedFingerprint = rs.getString("fingerprint");
                    if (isMismatch(storedFingerprint, context.requestFingerprint())) {
                        return RowInspection.resolved(
                                AcquireResult.fingerprintMismatch(storedFingerprint, context.requestFingerprint()));
                    }
                    return RowInspection.resolved(readDuplicate(rs));
                }

                Instant now = currentTime(conn);
                if (isStale(leaseExpiresTs, now)) {
                    boolean stolen = tryStealLease(conn, context, leaseId, now);
                    return stolen
                            ? RowInspection.resolved(AcquireResult.acquired(leaseId))
                            : RowInspection.keepPolling(Duration.ZERO);
                }

                if ("IN_PROGRESS".equals(status)) {
                    return RowInspection.keepPolling(remainingLease(leaseExpiresTs, now));
                }
                throw new IdempotencyCorruptRecordException(
                        "JDBC record for " + context.identity() + " has unknown status '" + status + "'");
            }
        }
    }

    private boolean tryStealLease(Connection conn, IdempotencyContext context, String leaseId, Instant now)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(STEAL_LEASE)) {
            setTimestamp(ps, 1, now.plus(context.leaseDuration()));
            ps.setString(2, context.requestFingerprint());
            ps.setString(3, leaseId);
            bindIdentity(ps, 4, context.identity());
            setTimestamp(ps, 6, now);
            return ps.executeUpdate() > 0;
        }
    }

    private static boolean isStale(Timestamp leaseExpiresTs, Instant now) {
        return leaseExpiresTs != null && leaseExpiresTs.toInstant().isBefore(now);
    }

    /** How much of the current holder's lease is left, floored at zero. */
    private static Duration remainingLease(Timestamp leaseExpiresTs, Instant now) {
        if (leaseExpiresTs == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(now, leaseExpiresTs.toInstant());
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Queries the current row state to build a precise error message when a {@code complete} or
     * {@code release} call finds no IN_PROGRESS row — distinguishing "key missing" from "key in
     * wrong state".
     *
     * <p><strong>Note:</strong> there is an inherent TOCTOU race between the failed UPDATE and
     * this diagnostic SELECT. Another thread may change the key's state in between, so the
     * message is best-effort and should only be used for logging or debugging, not control flow.
     */
    private IdempotencyLeaseLostException diagnoseMissingInProgress(
            Connection conn, IdempotencyIdentity identity, String leaseId, String operation) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_STATUS_AND_LEASE)) {
            bindIdentity(ps, 1, identity);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new IdempotencyLeaseLostException(
                            "Cannot " + operation + " " + identity + ": no entry exists or it expired");
                }
                String status = rs.getString("status");
                String currentLeaseId = rs.getString("lease_id");
                if ("IN_PROGRESS".equals(status) && !Objects.equals(currentLeaseId, leaseId)) {
                    return new IdempotencyLeaseLostException(
                            "Cannot " + operation + " " + identity + ": lease no longer owns the record");
                }
                return new IdempotencyLeaseLostException(
                        "Cannot " + operation + " " + identity + ": entry is " + status + ", expected IN_PROGRESS");
            }
        }
    }

    /** Binds {@code scope} at {@code index} and {@code idempotency_key} at {@code index + 1}. */
    private static void bindIdentity(PreparedStatement ps, int index, IdempotencyIdentity identity)
            throws SQLException {
        ps.setString(index, identity.scope());
        ps.setString(index + 1, identity.key());
    }

    /** Materializes the {@link AcquireResult.Duplicate} carried by a COMPLETE row. */
    private AcquireResult readDuplicate(ResultSet rs) throws SQLException {
        Timestamp completedAtTs = getTimestamp(rs, "completed_at");
        if (completedAtTs == null) {
            throw new IdempotencyCorruptRecordException("Completed JDBC record has no completed_at value");
        }
        String type = rs.getString("payload_type");
        if (type == null) {
            throw new IdempotencyCorruptRecordException("Completed JDBC record has no payload_type value");
        }
        byte[] body = rs.getBytes("payload");
        String attributesJson = rs.getString("attributes");
        Payload payload = new Payload(
                type,
                body != null ? body : new byte[0],
                attributesJson != null ? jsonToAttributes(attributesJson) : Map.of());
        return AcquireResult.duplicate(payload, completedAtTs.toInstant());
    }

    /**
     * A fingerprint present on only one side is not a mismatch — a caller that does not
     * fingerprint its payload cannot contradict one that does. See
     * {@link IdempotencyStore#tryAcquire}.
     */
    private static boolean isMismatch(String storedFingerprint, String incomingFingerprint) {
        return storedFingerprint != null
                && incomingFingerprint != null
                && !storedFingerprint.equals(incomingFingerprint);
    }

    /**
     * Whether the database rolled this statement back and expects the caller to try again -
     * a deadlock or a lock-wait timeout, reported as SQL state class 40.
     *
     * <p>{@code release} deletes the record, so the acquire path races an INSERT against
     * another caller's DELETE on the same primary key, and InnoDB breaks some of those races
     * by rolling one side back. That is a lost race, not an unreachable store, and the poll
     * loop already knows what to do with a lost race - it does the same thing for a duplicate
     * key. Surfacing it would also break the SPI contract that {@code tryAcquire} does its own
     * waiting and retrying and reports only an acquisition outcome.
     */
    private static boolean isTransientRollback(SQLException e) {
        return e instanceof SQLTransactionRollbackException
                || (e.getSQLState() != null && e.getSQLState().startsWith("40"));
    }

    private boolean isDuplicateKeyViolation(SQLException e) {
        String sqlState = e.getSQLState();
        return "23000".equals(sqlState) || "23505".equals(sqlState);
    }

    private Instant currentTime(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement();
                ResultSet result = statement.executeQuery(currentTimeSql(conn))) {
            if (!result.next()) {
                throw new SQLException("Database did not return the current timestamp");
            }
            Timestamp timestamp = result.getTimestamp(1, utcCalendar());
            if (timestamp == null) {
                throw new SQLException("Database returned a null current timestamp");
            }
            return timestamp.toInstant();
        }
    }

    private String currentTimeSql(Connection conn) throws SQLException {
        String sql = currentTimeSql;
        if (sql == null) {
            sql = conn.getMetaData()
                            .getDatabaseProductName()
                            .toLowerCase(Locale.ROOT)
                            .contains("postgresql")
                    ? SELECT_CURRENT_TIME_POSTGRESQL
                    : SELECT_CURRENT_TIME_MYSQL;
            currentTimeSql = sql;
        }
        return sql;
    }

    /**
     * Every timestamp crosses the driver boundary in UTC, in both directions, so a column
     * holds UTC wall-clock fields whatever zone the server and the JVM are in and reads back
     * as the instant it was written from.
     */
    private static void setTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setTimestamp(index, Timestamp.from(value), utcCalendar());
    }

    private static Timestamp getTimestamp(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column, utcCalendar());
    }

    /** {@link Calendar} is mutable and not thread-safe, so each call gets its own. */
    private static Calendar utcCalendar() {
        return Calendar.getInstance(UTC);
    }

    private static void requirePositiveDuration(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least 1ms, got: " + value);
        }
    }

    private static IdempotencyStoreUnavailableException unavailable(String operation, SQLException cause) {
        return new IdempotencyStoreUnavailableException("Failed to " + operation, cause);
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // Called while unwinding a failure that is already being reported. A broken
            // connection cannot be rolled back and will be discarded by the pool anyway,
            // so there is nothing this could usefully do or report.
        }
    }

    private static void resetAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // Restoring the borrowed connection's default on the way out. If it is already
            // broken the pool discards it, and masking the caller's real failure with this
            // one would only lose information.
        }
    }

    // --- JSON serialization ---

    static String attributesToJson(Map<String, String> attributes) {
        try {
            return OBJECT_MAPPER.writeValueAsString(attributes);
        } catch (JsonProcessingException e) {
            throw new IdempotencyStoreException("Failed to serialize payload attributes to JSON", e);
        }
    }

    static Map<String, String> jsonToAttributes(String json) {
        if (json == null || json.equals("{}")) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, ATTRIBUTES_TYPE);
        } catch (JsonProcessingException e) {
            throw new IdempotencyCorruptRecordException("Failed to deserialize payload attributes from JSON", e);
        }
    }
}
