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
 * <p>No Spring dependencies — only requires a {@link DataSource}.
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

    private static final String STEAL_LOCK =
            "UPDATE idempotency_records SET status = 'IN_PROGRESS', locked_at = ?, lock_expires_at = ?, "
                    + "response_code = NULL, response_headers = NULL, response_body = NULL "
                    + "WHERE idempotency_key = ? AND (status = 'FAILED' OR (status = 'IN_PROGRESS' AND lock_expires_at < ?))";

    private static final String COMPLETE =
            "UPDATE idempotency_records SET status = 'COMPLETE', response_code = ?, response_headers = ?, "
                    + "response_body = ?, completed_at = ?, lock_expires_at = NULL, expires_at = ? "
                    + "WHERE idempotency_key = ? AND status = 'IN_PROGRESS'";

    private static final String RELEASE =
            "UPDATE idempotency_records SET status = 'FAILED', locked_at = NULL, lock_expires_at = NULL, "
                    + "response_code = NULL, response_headers = NULL, response_body = NULL "
                    + "WHERE idempotency_key = ? AND status = 'IN_PROGRESS'";

    private static final String EXTEND_LOCK =
            "UPDATE idempotency_records SET lock_expires_at = ? WHERE idempotency_key = ? AND status = 'IN_PROGRESS'";

    private static final String PURGE_EXPIRED = "DELETE FROM idempotency_records WHERE "
            + "(status = 'COMPLETE' AND expires_at < ?) OR "
            + "(status = 'FAILED' AND expires_at < ?) OR "
            + "(status = 'IN_PROGRESS' AND lock_expires_at < ? AND expires_at < ?)";

    private final DataSource dataSource;
    private final long pollIntervalMs;

    public JdbcIdempotencyStore(DataSource dataSource) {
        this(dataSource, false);
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
                stmt.execute(sql);
            }
        } catch (IOException | SQLException e) {
            throw new IdempotencyStoreException("Failed to initialize schema", e);
        }
    }

    @Override
    public AcquireResult tryAcquire(IdempotencyContext context) {
        Instant deadline = Instant.now().plus(context.lockTimeout());

        // Step 1: Delete expired COMPLETE record + attempt INSERT in one connection
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement delPs = conn.prepareStatement(DELETE_EXPIRED)) {
                delPs.setString(1, context.key());
                delPs.setTimestamp(2, Timestamp.from(Instant.now()));
                delPs.executeUpdate();
            }
            try (PreparedStatement insPs = conn.prepareStatement(INSERT)) {
                Instant insertNow = Instant.now();
                insPs.setString(1, context.key());
                insPs.setTimestamp(2, Timestamp.from(insertNow));
                insPs.setTimestamp(3, Timestamp.from(insertNow.plus(context.lockTimeout())));
                insPs.setTimestamp(4, Timestamp.from(insertNow.plus(context.ttl())));
                insPs.executeUpdate();
                return AcquireResult.acquired();
            } catch (SQLException e) {
                if (!isDuplicateKeyViolation(e)) {
                    throw new IdempotencyStoreException("Failed to insert record for key '" + context.key() + "'", e);
                }
            }
        } catch (IdempotencyStoreException e) {
            throw e;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Failed during initial acquire for key '" + context.key() + "'", e);
        }

        // Step 2: Poll loop — only reached if first INSERT was a duplicate
        while (Instant.now().isBefore(deadline)) {
            // SELECT FOR UPDATE to check state
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(SELECT_FOR_UPDATE)) {
                    ps.setString(1, context.key());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            // Record disappeared — commit and retry INSERT below
                            conn.commit();
                        } else {
                            String status = rs.getString("status");
                            Timestamp lockExpiresTs = rs.getTimestamp("lock_expires_at");

                            if ("COMPLETE".equals(status)) {
                                StoredResponse response = readResponse(rs);
                                conn.commit();
                                return AcquireResult.duplicate(response);
                            }

                            if ("FAILED".equals(status) || isStaleInProgress(status, lockExpiresTs)) {
                                // Attempt to steal
                                Instant stealNow = Instant.now();
                                Instant stealLockExpires = stealNow.plus(context.lockTimeout());
                                try (PreparedStatement stealPs = conn.prepareStatement(STEAL_LOCK)) {
                                    stealPs.setTimestamp(1, Timestamp.from(stealNow));
                                    stealPs.setTimestamp(2, Timestamp.from(stealLockExpires));
                                    stealPs.setString(3, context.key());
                                    stealPs.setTimestamp(4, Timestamp.from(Instant.now()));
                                    int updated = stealPs.executeUpdate();
                                    conn.commit();
                                    if (updated > 0) {
                                        return AcquireResult.acquired();
                                    }
                                    // Someone else stole it — continue polling
                                }
                            } else {
                                // Active IN_PROGRESS — wait
                                conn.commit();
                            }
                        }
                    }
                } catch (SQLException e) {
                    try {
                        conn.rollback();
                    } catch (SQLException re) {
                        e.addSuppressed(re);
                    }
                    throw new IdempotencyStoreException("Failed during poll for key '" + context.key() + "'", e);
                }
            } catch (IdempotencyStoreException e) {
                throw e;
            } catch (SQLException e) {
                throw new IdempotencyStoreException("Failed to get connection for key '" + context.key() + "'", e);
            }

            // Sleep before next poll
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AcquireResult.lockTimeout(context.key());
            }

            // Retry INSERT after record disappeared or was stolen
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement ps = conn.prepareStatement(INSERT)) {
                Instant insertNow = Instant.now();
                ps.setString(1, context.key());
                ps.setTimestamp(2, Timestamp.from(insertNow));
                ps.setTimestamp(3, Timestamp.from(insertNow.plus(context.lockTimeout())));
                ps.setTimestamp(4, Timestamp.from(insertNow.plus(context.ttl())));
                ps.executeUpdate();
                return AcquireResult.acquired();
            } catch (SQLException e) {
                if (!isDuplicateKeyViolation(e)) {
                    throw new IdempotencyStoreException("Failed to insert record for key '" + context.key() + "'", e);
                }
                // Duplicate key — continue polling
            }
        }

        return AcquireResult.lockTimeout(context.key());
    }

    private static boolean isStaleInProgress(String status, Timestamp lockExpiresTs) {
        return "IN_PROGRESS".equals(status)
                && lockExpiresTs != null
                && lockExpiresTs.toInstant().isBefore(Instant.now());
    }

    @Override
    public void complete(String key, StoredResponse response, Duration ttl) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(COMPLETE)) {
            ps.setInt(1, response.statusCode());
            ps.setString(2, headersToJson(response.headers()));
            ps.setBytes(3, response.body());
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, Timestamp.from(expiresAt));
            ps.setString(6, key);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IdempotencyStoreException("Cannot complete key '" + key + "': no IN_PROGRESS entry exists");
            }
        } catch (IdempotencyStoreException e) {
            throw e;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Failed to complete key '" + key + "'", e);
        }
    }

    @Override
    public void release(String key) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(RELEASE)) {
            ps.setString(1, key);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IdempotencyStoreException("Cannot release key '" + key + "': no IN_PROGRESS entry exists");
            }
        } catch (IdempotencyStoreException e) {
            throw e;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Failed to release key '" + key + "'", e);
        }
    }

    @Override
    public void extendLock(String key, Duration extension) {
        Instant newExpiry = Instant.now().plus(extension);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(EXTEND_LOCK)) {
            ps.setTimestamp(1, Timestamp.from(newExpiry));
            ps.setString(2, key);
            ps.executeUpdate();
            // Silently ignore if no rows updated
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
        Instant now = Instant.now();
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

    private StoredResponse readResponse(ResultSet rs) throws SQLException {
        int statusCode = rs.getInt("response_code");
        String headersJson = rs.getString("response_headers");
        byte[] body = rs.getBytes("response_body");
        Timestamp completedAtTs = rs.getTimestamp("completed_at");

        Map<String, List<String>> headers = headersJson != null ? jsonToHeaders(headersJson) : Map.of();
        byte[] responseBody = body != null ? body : new byte[0];
        Instant completedAt = completedAtTs != null ? completedAtTs.toInstant() : Instant.now();

        return new StoredResponse(statusCode, headers, responseBody, completedAt);
    }

    private boolean isDuplicateKeyViolation(SQLException e) {
        String sqlState = e.getSQLState();
        return "23000".equals(sqlState) || "23505".equals(sqlState);
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
                        if (i + 5 < s.length()) {
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
