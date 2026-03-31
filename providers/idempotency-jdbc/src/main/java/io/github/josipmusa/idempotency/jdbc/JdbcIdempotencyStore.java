package io.github.josipmusa.idempotency.jdbc;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p>Initializes schema by default</p>
 *
 * <p>No Spring dependencies — only requires a {@link DataSource}.
 *
 * <p><strong>Database compatibility:</strong> The bundled schema
 * ({@code idempotency-schema.sql}) uses MySQL syntax. For PostgreSQL,
 * replace {@code BLOB} with {@code BYTEA} and
 * {@code CURRENT_TIMESTAMP(6)} with {@code CURRENT_TIMESTAMP}.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final long DEFAULT_POLL_INTERVAL_MS = 100;

    private static final String DELETE_EXPIRED =
            "DELETE FROM idempotency_records WHERE idempotency_key = ? AND expires_at < ? AND status = 'COMPLETE'";

    private static final String INSERT =
            "INSERT INTO idempotency_records (idempotency_key, status, locked_at, lock_expires_at, expires_at) "
                    + "VALUES (?, 'IN_PROGRESS', ?, ?, ?)";

    private static final String SELECT_FOR_UPDATE =
            "SELECT status, lock_expires_at, response_code, response_headers, response_body, completed_at "
                    + "FROM idempotency_records WHERE idempotency_key = ? FOR UPDATE";

    private static final String SELECT_STATUS = "SELECT status FROM idempotency_records WHERE idempotency_key = ?";

    private static final String STEAL_LOCK =
            "UPDATE idempotency_records SET status = 'IN_PROGRESS', locked_at = ?, lock_expires_at = ?, "
                    + "response_code = NULL, response_headers = NULL, response_body = NULL, completed_at = NULL "
                    + "WHERE idempotency_key = ? AND (status = 'FAILED' OR (status = 'IN_PROGRESS' AND lock_expires_at < ?))";

    private static final String COMPLETE =
            "UPDATE idempotency_records SET status = 'COMPLETE', response_code = ?, response_headers = ?, "
                    + "response_body = ?, completed_at = ?, lock_expires_at = NULL, expires_at = ? "
                    + "WHERE idempotency_key = ? AND status = 'IN_PROGRESS'";

    private static final String RELEASE =
            "UPDATE idempotency_records SET status = 'FAILED', expires_at = lock_expires_at, locked_at = NULL, lock_expires_at = NULL, "
                    + "response_code = NULL, response_headers = NULL, response_body = NULL "
                    + "WHERE idempotency_key = ? AND status = 'IN_PROGRESS'";

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
     * Executes the schema DDL from the bundled {@code idempotency-schema.sql} file. The schema
     * uses {@code CREATE TABLE IF NOT EXISTS}, so calling this method more than once is safe.
     *
     * <p><strong>Note:</strong> the entire SQL file is executed as a single statement. If the
     * schema ever grows to multiple statements, switch to executing each statement individually.
     */
    private void initSchema() {
        try (InputStream is = getClass().getResourceAsStream("/idempotency-schema.sql")) {
            if (is == null) {
                throw new IdempotencyStoreException("Schema file /idempotency-schema.sql not found on classpath");
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
                // Split by semicolons to handle multiple statements
                // MySQL JDBC does not support multiple statements in a single execute() call
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
            try (PreparedStatement ps = conn.prepareStatement(RELEASE)) {
                ps.setString(1, key);
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    throw new IdempotencyStoreException(diagnoseMissingInProgress(conn, key, "release"));
                }
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
            ps.setString(3, context.key());
            ps.setTimestamp(4, Timestamp.from(now));
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

    // --- JSON serialization (no external library) ---

    static String headersToJson(Map<String, List<String>> headers) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean firstEntry = true;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!firstEntry) {
                sb.append(',');
            }
            firstEntry = false;
            sb.append('"');
            escapeJson(sb, entry.getKey());
            sb.append("\":[");
            boolean firstVal = true;
            for (String val : entry.getValue()) {
                if (!firstVal) {
                    sb.append(',');
                }
                firstVal = false;
                sb.append('"');
                escapeJson(sb, val);
                sb.append('"');
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    static Map<String, List<String>> jsonToHeaders(String json) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (json == null || json.equals("{}")) {
            return result;
        }
        // Strip outer braces
        String inner = json.substring(1, json.length() - 1).trim();
        if (inner.isEmpty()) {
            return result;
        }
        int pos = 0;
        while (pos < inner.length()) {
            // Read key
            pos = inner.indexOf('"', pos) + 1;
            int keyEnd = findUnescapedQuote(inner, pos);
            String key = unescapeJson(inner.substring(pos, keyEnd));
            pos = keyEnd + 1;
            // Skip ':'
            pos = inner.indexOf(':', pos) + 1;
            // Skip '['
            pos = inner.indexOf('[', pos) + 1;
            // Read values
            List<String> values = new ArrayList<>();
            while (pos < inner.length() && inner.charAt(pos) != ']') {
                if (inner.charAt(pos) == ',') {
                    pos++;
                }
                if (inner.charAt(pos) == '"') {
                    pos++;
                    int valEnd = findUnescapedQuote(inner, pos);
                    values.add(unescapeJson(inner.substring(pos, valEnd)));
                    pos = valEnd + 1;
                }
            }
            pos++; // skip ']'
            result.put(key, List.copyOf(values));
            // Skip comma if present
            if (pos < inner.length() && inner.charAt(pos) == ',') {
                pos++;
            }
        }
        return Map.copyOf(result);
    }

    private static void escapeJson(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }

    private static int findUnescapedQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                i++; // skip escaped character
            } else if (s.charAt(i) == '"') {
                return i;
            }
        }
        return s.length();
    }

    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> {
                        sb.append('"');
                        i++;
                    }
                    case '\\' -> {
                        sb.append('\\');
                        i++;
                    }
                    case 'n' -> {
                        sb.append('\n');
                        i++;
                    }
                    case 'r' -> {
                        sb.append('\r');
                        i++;
                    }
                    case 't' -> {
                        sb.append('\t');
                        i++;
                    }
                    case 'u' -> {
                        if (i + 5 <= s.length()) {
                            String hex = s.substring(i + 2, i + 6);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 5;
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
