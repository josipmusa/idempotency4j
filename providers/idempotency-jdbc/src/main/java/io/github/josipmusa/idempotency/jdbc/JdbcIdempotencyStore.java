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
import io.github.josipmusa.idempotency.core.IdempotencyPayload;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.NoPayload;
import io.github.josipmusa.idempotency.core.StoredResponse;
import io.github.josipmusa.idempotency.core.exception.IdempotencyCorruptRecordException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreUnavailableException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final String SELECT_CURRENT_TIME = "SELECT CURRENT_TIMESTAMP(3)";

    private static final String DELETE_EXPIRED = "DELETE FROM idempotency_records "
            + "WHERE scope = ? AND idempotency_key = ? AND expires_at < ? AND status = 'COMPLETE'";

    private static final String INSERT = "INSERT INTO idempotency_records "
            + "(scope, idempotency_key, status, locked_at, lock_expires_at, expires_at, request_fingerprint, lease_id, lock_timeout_ms) "
            + "VALUES (?, ?, 'IN_PROGRESS', ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_FOR_UPDATE =
            "SELECT status, lock_expires_at, response_code, response_headers, response_body, completed_at, request_fingerprint "
                    + "FROM idempotency_records WHERE scope = ? AND idempotency_key = ? FOR UPDATE";

    private static final String SELECT_STATUS_AND_LEASE =
            "SELECT status, lease_id FROM idempotency_records WHERE scope = ? AND idempotency_key = ?";

    private static final String STEAL_LOCK =
            "UPDATE idempotency_records SET status = 'IN_PROGRESS', locked_at = ?, lock_expires_at = ?, "
                    + "request_fingerprint = ?, lease_id = ?, lock_timeout_ms = ?, "
                    + "response_code = NULL, response_headers = NULL, response_body = NULL, completed_at = NULL "
                    + "WHERE scope = ? AND idempotency_key = ? "
                    + "AND (status = 'FAILED' OR (status = 'IN_PROGRESS' AND lock_expires_at < ?))";

    private static final String COMPLETE =
            "UPDATE idempotency_records SET status = 'COMPLETE', response_code = ?, response_headers = ?, "
                    + "response_body = ?, completed_at = ?, lock_expires_at = NULL, lease_id = NULL, expires_at = ? "
                    + "WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND lease_id = ?";

    private static final String RELEASE =
            "UPDATE idempotency_records SET status = 'FAILED', expires_at = ?, locked_at = NULL, lock_expires_at = NULL, "
                    + "lease_id = NULL, response_code = NULL, response_headers = NULL, response_body = NULL "
                    + "WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND lease_id = ?";

    private static final String SELECT_LOCK_TIMEOUT_FOR_UPDATE = "SELECT lock_timeout_ms FROM idempotency_records "
            + "WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND lease_id = ? FOR UPDATE";

    private static final String EXTEND_LOCK = "UPDATE idempotency_records SET lock_expires_at = ? "
            + "WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND lease_id = ?";

    private static final String PURGE_EXPIRED = "DELETE FROM idempotency_records WHERE "
            + "(status = 'COMPLETE' AND expires_at < ?) OR "
            + "(status = 'FAILED' AND expires_at < ?) OR "
            + "(status = 'IN_PROGRESS' AND lock_expires_at < ? AND expires_at < ?)";

    /**
     * Outcome of inspecting a locked row during the poll loop.
     *
     * <ul>
     *   <li>{@code result} non-null — outcome is fully resolved (COMPLETE or lock stolen)</li>
     *   <li>{@code rowGone} true — row disappeared; retry insert immediately, no sleep</li>
     *   <li>both null/false — row is active IN_PROGRESS; sleep and poll again</li>
     * </ul>
     */
    private record RowInspection(AcquireResult result, boolean rowGone) {
        static RowInspection resolved(AcquireResult r) {
            return new RowInspection(r, false);
        }

        static RowInspection gone() {
            return new RowInspection(null, true);
        }

        static RowInspection keepPolling() {
            return new RowInspection(null, false);
        }
    }

    private final DataSource dataSource;
    private final long pollIntervalMs;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, List<String>>> HEADERS_TYPE = new TypeReference<>() {};

    public JdbcIdempotencyStore(DataSource dataSource) {
        this(dataSource, true);
    }

    public JdbcIdempotencyStore(DataSource dataSource, boolean initSchema) {
        this(dataSource, initSchema, DEFAULT_POLL_INTERVAL_MS);
    }

    public JdbcIdempotencyStore(DataSource dataSource, boolean initSchema, long pollIntervalMs) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive, got: " + pollIntervalMs);
        }
        this.pollIntervalMs = pollIntervalMs;
        if (initSchema) {
            initSchema();
        }
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
        ensureLeaseColumn();
    }

    private void ensureLeaseColumn() {
        try (Connection conn = dataSource.getConnection()) {
            try (ResultSet columns =
                    conn.getMetaData().getColumns(conn.getCatalog(), null, "idempotency_records", "lease_id")) {
                if (columns.next()) {
                    return;
                }
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE idempotency_records ADD COLUMN lease_id VARCHAR(36) NULL");
            } catch (SQLException e) {
                if (e.getErrorCode() != 1060 && !"42701".equals(e.getSQLState())) {
                    throw e;
                }
            }
        } catch (SQLException e) {
            throw new IdempotencyStoreUnavailableException("Failed to add lease fencing column", e);
        }
    }

    private void createMysqlIndex() {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE INDEX idx_idempotency_status_expires "
                    + "ON idempotency_records (status, expires_at, lock_expires_at)");
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
        long timeoutNanos = context.lockTimeout().toNanos();
        String leaseId = UUID.randomUUID().toString();
        IdempotencyIdentity identity = context.identity();

        // Fast path: evict any expired COMPLETE record for this identity, then insert a fresh
        // IN_PROGRESS row. The DELETE and INSERT run as separate autocommit statements —
        // no explicit transaction. If a concurrent caller inserts between our DELETE and
        // INSERT, the INSERT throws a duplicate-key violation. The poll loop below handles
        // that correctly, so the lack of an explicit transaction here is intentional.
        if (tryInsert(context, leaseId)) {
            return AcquireResult.acquired(leaseId);
        }

        // Duplicate key — poll until we can acquire, the operation completes, or timeout
        while (System.nanoTime() - startedAtNanos < timeoutNanos) {
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
            try {
                long remainingNanos = timeoutNanos - (System.nanoTime() - startedAtNanos);
                if (remainingNanos <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(pollIntervalMs)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AcquireResult.lockTimeout(identity);
            }
        }

        return AcquireResult.lockTimeout(identity);
    }

    @Override
    public void complete(IdempotencyIdentity identity, String leaseId, IdempotencyPayload payload, Duration ttl) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        requirePositiveDuration(ttl, "ttl");
        try (Connection conn = dataSource.getConnection()) {
            Instant now = currentTime(conn);
            try (PreparedStatement ps = conn.prepareStatement(COMPLETE)) {
                bindPayload(ps, payload);
                setTimestamp(ps, 4, payload.completedAt());
                setTimestamp(ps, 5, now.plus(ttl));
                bindIdentity(ps, 6, identity);
                ps.setString(8, leaseId);
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    throw diagnoseMissingInProgress(conn, identity, leaseId, "complete");
                }
            }
        } catch (SQLException e) {
            throw unavailable("complete " + identity, e);
        }
    }

    /**
     * Binds the response columns of {@link #COMPLETE}.
     *
     * <p>{@link NoPayload} leaves {@code response_code}, {@code response_headers} and
     * {@code response_body} NULL — those columns are already nullable, so no schema change
     * is needed. A NULL {@code response_code} on a COMPLETE row is what
     * {@link #readPayload} reads back as {@code NoPayload}.
     */
    private static void bindPayload(PreparedStatement ps, IdempotencyPayload payload) throws SQLException {
        switch (payload) {
            case StoredResponse response -> {
                ps.setInt(1, response.statusCode());
                ps.setString(2, headersToJson(response.headers()));
                ps.setBytes(3, response.body());
            }
            case NoPayload ignored -> {
                ps.setNull(1, Types.INTEGER);
                ps.setNull(2, Types.VARCHAR);
                ps.setNull(3, Types.VARBINARY);
            }
        }
    }

    @Override
    public void release(IdempotencyIdentity identity, String leaseId) {
        Objects.requireNonNull(identity, "identity must not be null");
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long lockTimeoutMs;
                try (PreparedStatement sel = conn.prepareStatement(SELECT_LOCK_TIMEOUT_FOR_UPDATE)) {
                    bindIdentity(sel, 1, identity);
                    sel.setString(3, leaseId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) {
                            throw diagnoseMissingInProgress(conn, identity, leaseId, "release");
                        }
                        lockTimeoutMs = rs.getLong("lock_timeout_ms");
                    }
                }
                Instant failedExpiry = currentTime(conn).plusMillis(lockTimeoutMs);
                try (PreparedStatement ps = conn.prepareStatement(RELEASE)) {
                    setTimestamp(ps, 1, failedExpiry);
                    bindIdentity(ps, 2, identity);
                    ps.setString(4, leaseId);
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
        } catch (SQLException e) {
            throw unavailable("release " + identity, e);
        }
    }

    @Override
    public void extendLock(IdempotencyIdentity identity, String leaseId, Duration extension) {
        Objects.requireNonNull(identity, "identity must not be null");
        requirePositiveDuration(extension, "extension");
        try (Connection conn = dataSource.getConnection()) {
            Instant newExpiry = currentTime(conn).plus(extension);
            try (PreparedStatement ps = conn.prepareStatement(EXTEND_LOCK)) {
                setTimestamp(ps, 1, newExpiry);
                bindIdentity(ps, 2, identity);
                ps.setString(4, leaseId);
                ps.executeUpdate();
                // Silently ignore if no rows updated - heartbeat may fire after completion.
            }
        } catch (SQLException e) {
            throw unavailable("extend lock for " + identity, e);
        }
    }

    /**
     * Deletes stale records from the database in a single query.
     *
     * <p>Removes COMPLETE and FAILED rows whose {@code expires_at} is in
     * the past, and IN_PROGRESS rows where both {@code lock_expires_at}
     * and {@code expires_at} are in the past.
     *
     * @return the number of rows deleted
     */
    @Override
    public int purgeExpired() {
        try (Connection conn = dataSource.getConnection()) {
            Instant now = currentTime(conn);
            try (PreparedStatement ps = conn.prepareStatement(PURGE_EXPIRED)) {
                setTimestamp(ps, 1, now);
                setTimestamp(ps, 2, now);
                setTimestamp(ps, 3, now);
                setTimestamp(ps, 4, now);
                return ps.executeUpdate();
            }
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
        try (Connection conn = dataSource.getConnection()) {
            Instant now = currentTime(conn);
            try (PreparedStatement del = conn.prepareStatement(DELETE_EXPIRED)) {
                bindIdentity(del, 1, identity);
                setTimestamp(del, 3, now);
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(INSERT)) {
                bindIdentity(ins, 1, identity);
                setTimestamp(ins, 3, now);
                setTimestamp(ins, 4, now.plus(context.lockTimeout()));
                setTimestamp(ins, 5, now.plus(context.ttl()));
                ins.setString(6, context.requestFingerprint());
                ins.setString(7, leaseId);
                ins.setLong(8, context.lockTimeout().toMillis());
                ins.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (!isDuplicateKeyViolation(e)) {
                    throw unavailable("insert record for " + identity, e);
                }
                return false; // duplicate key — row already exists
            }
        } catch (SQLException e) {
            throw unavailable("perform initial acquire for " + identity, e);
        }
    }

    /**
     * Opens a {@code SELECT FOR UPDATE} transaction to inspect the current row state and
     * decide what to do next.
     */
    private RowInspection inspectRow(IdempotencyContext context, String leaseId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                RowInspection inspection = doInspectRow(conn, context, leaseId);
                conn.commit();
                return inspection;
            } catch (IdempotencyStoreException e) {
                rollbackQuietly(conn);
                throw e;
            } catch (SQLException e) {
                rollbackQuietly(conn);
                throw unavailable("poll " + context.identity(), e);
            } finally {
                resetAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw unavailable("get connection for " + context.identity(), e);
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
                Timestamp lockExpiresTs = rs.getTimestamp("lock_expires_at");

                if ("COMPLETE".equals(status)) {
                    String storedFingerprint = rs.getString("request_fingerprint");
                    if (isMismatch(storedFingerprint, context.requestFingerprint())) {
                        return RowInspection.resolved(
                                AcquireResult.fingerprintMismatch(storedFingerprint, context.requestFingerprint()));
                    }
                    return RowInspection.resolved(AcquireResult.duplicate(readPayload(rs)));
                }

                Instant now = currentTime(conn);
                if ("FAILED".equals(status) || isStale(lockExpiresTs, now)) {
                    boolean stolen = tryStealLock(conn, context, leaseId, now);
                    return stolen
                            ? RowInspection.resolved(AcquireResult.acquired(leaseId))
                            : RowInspection.keepPolling();
                }

                if ("IN_PROGRESS".equals(status)) {
                    return RowInspection.keepPolling();
                }
                throw new IdempotencyCorruptRecordException(
                        "JDBC record for " + context.identity() + " has unknown status '" + status + "'");
            }
        }
    }

    private boolean tryStealLock(Connection conn, IdempotencyContext context, String leaseId, Instant now)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(STEAL_LOCK)) {
            setTimestamp(ps, 1, now);
            setTimestamp(ps, 2, now.plus(context.lockTimeout()));
            ps.setString(3, context.requestFingerprint());
            ps.setString(4, leaseId);
            ps.setLong(5, context.lockTimeout().toMillis());
            bindIdentity(ps, 6, context.identity());
            setTimestamp(ps, 8, now);
            return ps.executeUpdate() > 0;
        }
    }

    private static boolean isStale(Timestamp lockExpiresTs, Instant now) {
        return lockExpiresTs != null && lockExpiresTs.toInstant().isBefore(now);
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

    /**
     * Materializes the payload of a COMPLETE row.
     *
     * <p>A NULL {@code response_code} means the record was completed by a caller with
     * nothing to replay, and reads back as {@link NoPayload}.
     */
    private IdempotencyPayload readPayload(ResultSet rs) throws SQLException {
        int statusCode = rs.getInt("response_code");
        boolean noResponse = rs.wasNull();
        Timestamp completedAtTs = rs.getTimestamp("completed_at");
        if (completedAtTs == null) {
            throw new IdempotencyCorruptRecordException("Completed JDBC record has no completed_at value");
        }
        Instant completedAt = completedAtTs.toInstant();
        if (noResponse) {
            return NoPayload.at(completedAt);
        }

        String headersJson = rs.getString("response_headers");
        byte[] body = rs.getBytes("response_body");
        Map<String, List<String>> headers = headersJson != null ? jsonToHeaders(headersJson) : Map.of();
        byte[] responseBody = body != null ? body : new byte[0];

        return new StoredResponse(statusCode, headers, responseBody, completedAt);
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

    private boolean isDuplicateKeyViolation(SQLException e) {
        String sqlState = e.getSQLState();
        return "23000".equals(sqlState) || "23505".equals(sqlState);
    }

    private static Instant currentTime(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement();
                ResultSet result = statement.executeQuery(SELECT_CURRENT_TIME)) {
            if (!result.next()) {
                throw new SQLException("Database did not return CURRENT_TIMESTAMP");
            }
            Timestamp timestamp = result.getTimestamp(1);
            if (timestamp == null) {
                throw new SQLException("Database returned null CURRENT_TIMESTAMP");
            }
            return timestamp.toInstant();
        }
    }

    private static void setTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setTimestamp(index, Timestamp.from(value));
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

    static String headersToJson(Map<String, List<String>> headers) {
        try {
            return OBJECT_MAPPER.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IdempotencyStoreException("Failed to serialize response headers to JSON", e);
        }
    }

    static Map<String, List<String>> jsonToHeaders(String json) {
        if (json == null || json.equals("{}")) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, HEADERS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IdempotencyCorruptRecordException("Failed to deserialize response headers from JSON", e);
        }
    }
}
