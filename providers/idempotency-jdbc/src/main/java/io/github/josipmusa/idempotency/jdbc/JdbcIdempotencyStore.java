package io.github.josipmusa.idempotency.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.josipmusa.core.AcquireResult;
import io.github.josipmusa.core.IdempotencyContext;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.core.StoredResponse;
import io.github.josipmusa.core.exception.IdempotencyStoreException;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private static final String DELETE_EXPIRED =
            "DELETE FROM idempotency_records WHERE idempotency_key = ? AND expires_at < ? AND status = 'COMPLETE'";

    private static final String INSERT = "INSERT INTO idempotency_records "
            + "(idempotency_key, status, locked_at, lock_expires_at, expires_at, request_fingerprint, lock_timeout_ms) "
            + "VALUES (?, 'IN_PROGRESS', ?, ?, ?, ?, ?)";

    private static final String SELECT_FOR_UPDATE =
            "SELECT status, lock_expires_at, response_code, response_headers, response_body, completed_at, request_fingerprint "
                    + "FROM idempotency_records WHERE idempotency_key = ? FOR UPDATE";

    private static final String SELECT_STATUS = "SELECT status FROM idempotency_records WHERE idempotency_key = ?";

    private static final String STEAL_LOCK =
            "UPDATE idempotency_records SET status = 'IN_PROGRESS', locked_at = ?, lock_expires_at = ?, "
                    + "request_fingerprint = ?, lock_timeout_ms = ?, "
                    + "response_code = NULL, response_headers = NULL, response_body = NULL, completed_at = NULL "
                    + "WHERE idempotency_key = ? AND (status = 'FAILED' OR (status = 'IN_PROGRESS' AND lock_expires_at < ?))";

    private static final String COMPLETE =
            "UPDATE idempotency_records SET status = 'COMPLETE', response_code = ?, response_headers = ?, "
                    + "response_body = ?, completed_at = ?, lock_expires_at = NULL, expires_at = ? "
                    + "WHERE idempotency_key = ? AND status = 'IN_PROGRESS'";

    private static final String RELEASE =
            "UPDATE idempotency_records SET status = 'FAILED', expires_at = ?, locked_at = NULL, lock_expires_at = NULL, "
                    + "response_code = NULL, response_headers = NULL, response_body = NULL "
                    + "WHERE idempotency_key = ? AND status = 'IN_PROGRESS'";

    private static final String SELECT_LOCK_TIMEOUT_FOR_UPDATE = "SELECT lock_timeout_ms FROM idempotency_records "
            + "WHERE idempotency_key = ? AND status = 'IN_PROGRESS' FOR UPDATE";

    private static final String EXTEND_LOCK =
            "UPDATE idempotency_records SET lock_expires_at = ? WHERE idempotency_key = ? AND status = 'IN_PROGRESS'";

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
    private final Clock clock;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, List<String>>> HEADERS_TYPE = new TypeReference<>() {};

    public JdbcIdempotencyStore(DataSource dataSource) {
        this(dataSource, true);
    }

    public JdbcIdempotencyStore(DataSource dataSource, boolean initSchema) {
        this(dataSource, initSchema, DEFAULT_POLL_INTERVAL_MS);
    }

    public JdbcIdempotencyStore(DataSource dataSource, boolean initSchema, long pollIntervalMs) {
        this(dataSource, initSchema, pollIntervalMs, Clock.systemUTC());
    }

    public JdbcIdempotencyStore(DataSource dataSource, boolean initSchema, long pollIntervalMs, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive, got: " + pollIntervalMs);
        }
        this.pollIntervalMs = pollIntervalMs;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
            throw new IdempotencyStoreException("Failed to detect database dialect", e);
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
            throw new IdempotencyStoreException("Failed to initialize schema", e);
        }

        if (!dialect.contains("postgresql")) {
            createMysqlIndex();
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
                throw new IdempotencyStoreException("Failed to create index", e);
            }
        }
    }

    @Override
    public AcquireResult tryAcquire(IdempotencyContext context) {
        Instant deadline = clock.instant().plus(context.lockTimeout());

        // Fast path: evict any expired COMPLETE record for this key, then insert a fresh
        // IN_PROGRESS row. The DELETE and INSERT run as separate autocommit statements —
        // no explicit transaction. If a concurrent caller inserts between our DELETE and
        // INSERT, the INSERT throws a duplicate-key violation. The poll loop below handles
        // that correctly, so the lack of an explicit transaction here is intentional.
        if (tryInsert(context)) {
            return AcquireResult.acquired();
        }

        // Duplicate key — poll until we can acquire, the operation completes, or timeout
        while (clock.instant().isBefore(deadline)) {
            RowInspection inspection = inspectRow(context);

            if (inspection.result() != null) {
                return inspection.result();
            }

            if (inspection.rowGone()) {
                // Row disappeared between our INSERT attempt and the SELECT FOR UPDATE.
                // Retry the insert immediately — no sleep needed, the slot is free.
                if (tryInsert(context)) return AcquireResult.acquired();
                continue; // someone else inserted first, loop back to poll
            }

            // Active IN_PROGRESS — sleep before next poll
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AcquireResult.lockTimeout(context.key());
            }
        }

        return AcquireResult.lockTimeout(context.key());
    }

    @Override
    public void complete(String key, StoredResponse response, Duration ttl) {
        Instant now = clock.instant();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(COMPLETE)) {
                ps.setInt(1, response.statusCode());
                ps.setString(2, headersToJson(response.headers()));
                ps.setBytes(3, response.body());
                ps.setTimestamp(4, Timestamp.from(now));
                ps.setTimestamp(5, Timestamp.from(now.plus(ttl)));
                ps.setString(6, key);
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    throw new IdempotencyStoreException(diagnoseMissingInProgress(conn, key, "complete"));
                }
            }
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Failed to complete key '" + key + "'", e);
        }
    }

    @Override
    public void release(String key) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long lockTimeoutMs;
                try (PreparedStatement sel = conn.prepareStatement(SELECT_LOCK_TIMEOUT_FOR_UPDATE)) {
                    sel.setString(1, key);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) {
                            throw new IdempotencyStoreException(diagnoseMissingInProgress(conn, key, "release"));
                        }
                        lockTimeoutMs = rs.getLong("lock_timeout_ms");
                    }
                }
                Instant failedExpiry = clock.instant().plus(Duration.ofMillis(lockTimeoutMs));
                try (PreparedStatement ps = conn.prepareStatement(RELEASE)) {
                    ps.setTimestamp(1, Timestamp.from(failedExpiry));
                    ps.setString(2, key);
                    if (ps.executeUpdate() == 0) {
                        throw new IdempotencyStoreException(diagnoseMissingInProgress(conn, key, "release"));
                    }
                }
                conn.commit();
            } catch (IdempotencyStoreException e) {
                rollbackQuietly(conn);
                throw e;
            } catch (SQLException e) {
                rollbackQuietly(conn);
                throw new IdempotencyStoreException("Failed to release key '" + key + "'", e);
            } finally {
                resetAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Failed to release key '" + key + "'", e);
        }
    }

    @Override
    public void extendLock(String key, Duration extension) {
        Instant newExpiry = clock.instant().plus(extension);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(EXTEND_LOCK)) {
            ps.setTimestamp(1, Timestamp.from(newExpiry));
            ps.setString(2, key);
            ps.executeUpdate();
            // Silently ignore if no rows updated — heartbeat may fire after completion
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Failed to extend lock for key '" + key + "'", e);
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
    public int purgeExpired() {
        Instant now = clock.instant();
        Timestamp ts = Timestamp.from(now);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(PURGE_EXPIRED)) {
            ps.setTimestamp(1, ts);
            ps.setTimestamp(2, ts);
            ps.setTimestamp(3, ts);
            ps.setTimestamp(4, ts);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Failed to purge expired records", e);
        }
    }

    /**
     * Evicts an expired COMPLETE record for this key, then attempts an INSERT of a fresh
     * IN_PROGRESS row.
     *
     * @return {@code true} if the row was inserted (lock acquired), {@code false} if a row
     *     already exists (duplicate key)
     */
    private boolean tryInsert(IdempotencyContext context) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement del = conn.prepareStatement(DELETE_EXPIRED)) {
                del.setString(1, context.key());
                del.setTimestamp(2, Timestamp.from(clock.instant()));
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(INSERT)) {
                Instant now = clock.instant();
                ins.setString(1, context.key());
                ins.setTimestamp(2, Timestamp.from(now));
                ins.setTimestamp(3, Timestamp.from(now.plus(context.lockTimeout())));
                ins.setTimestamp(4, Timestamp.from(now.plus(context.ttl())));
                ins.setString(5, context.requestFingerprint());
                ins.setLong(6, context.lockTimeout().toMillis());
                ins.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (!isDuplicateKeyViolation(e)) {
                    throw new IdempotencyStoreException("Failed to insert record for key '" + context.key() + "'", e);
                }
                return false; // duplicate key — row already exists
            }
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Failed during initial acquire for key '" + context.key() + "'", e);
        }
    }

    /**
     * Opens a {@code SELECT FOR UPDATE} transaction to inspect the current row state and
     * decide what to do next.
     */
    private RowInspection inspectRow(IdempotencyContext context) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                RowInspection inspection = doInspectRow(conn, context);
                conn.commit();
                return inspection;
            } catch (SQLException e) {
                rollbackQuietly(conn);
                throw new IdempotencyStoreException("Failed during poll for key '" + context.key() + "'", e);
            } finally {
                resetAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Failed to get connection for key '" + context.key() + "'", e);
        }
    }

    private RowInspection doInspectRow(Connection conn, IdempotencyContext context) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_FOR_UPDATE)) {
            ps.setString(1, context.key());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return RowInspection.gone();
                }

                String status = rs.getString("status");
                Timestamp lockExpiresTs = rs.getTimestamp("lock_expires_at");

                if ("COMPLETE".equals(status)) {
                    String storedFingerprint = rs.getString("request_fingerprint");
                    if (storedFingerprint != null && !storedFingerprint.equals(context.requestFingerprint())) {
                        return RowInspection.resolved(
                                AcquireResult.fingerprintMismatch(storedFingerprint, context.requestFingerprint()));
                    }
                    return RowInspection.resolved(AcquireResult.duplicate(readResponse(rs)));
                }

                if ("FAILED".equals(status) || isStale(lockExpiresTs)) {
                    boolean stolen = tryStealLock(conn, context);
                    return stolen ? RowInspection.resolved(AcquireResult.acquired()) : RowInspection.keepPolling();
                }

                return RowInspection.keepPolling(); // active IN_PROGRESS
            }
        }
    }

    private boolean tryStealLock(Connection conn, IdempotencyContext context) throws SQLException {
        Instant now = clock.instant();
        try (PreparedStatement ps = conn.prepareStatement(STEAL_LOCK)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setTimestamp(2, Timestamp.from(now.plus(context.lockTimeout())));
            ps.setString(3, context.requestFingerprint());
            ps.setLong(4, context.lockTimeout().toMillis());
            ps.setString(5, context.key());
            ps.setTimestamp(6, Timestamp.from(now));
            return ps.executeUpdate() > 0;
        }
    }

    private boolean isStale(Timestamp lockExpiresTs) {
        return lockExpiresTs != null && lockExpiresTs.toInstant().isBefore(clock.instant());
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
    private String diagnoseMissingInProgress(Connection conn, String key, String operation) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_STATUS)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "Cannot " + operation + " key '" + key + "': no entry exists. Was tryAcquire called?";
                }
                String status = rs.getString("status");
                return "Cannot " + operation + " key '" + key + "': entry is " + status + ", expected IN_PROGRESS";
            }
        }
    }

    private StoredResponse readResponse(ResultSet rs) throws SQLException {
        int statusCode = rs.getInt("response_code");
        String headersJson = rs.getString("response_headers");
        byte[] body = rs.getBytes("response_body");
        Timestamp completedAtTs = rs.getTimestamp("completed_at");
        if (completedAtTs == null) {
            throw new IdempotencyStoreException("Completed request doesn't have completed_at value");
        }

        Map<String, List<String>> headers = headersJson != null ? jsonToHeaders(headersJson) : Map.of();
        byte[] responseBody = body != null ? body : new byte[0];
        Instant completedAt = completedAtTs.toInstant();

        return new StoredResponse(statusCode, headers, responseBody, completedAt);
    }

    private boolean isDuplicateKeyViolation(SQLException e) {
        String sqlState = e.getSQLState();
        return "23000".equals(sqlState) || "23505".equals(sqlState);
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void resetAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
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
            throw new IdempotencyStoreException("Failed to deserialize response headers from JSON", e);
        }
    }
}
