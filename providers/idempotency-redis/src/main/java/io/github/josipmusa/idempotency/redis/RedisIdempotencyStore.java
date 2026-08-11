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
package io.github.josipmusa.idempotency.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.StoredResponse;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis implementation of {@link IdempotencyStore}, backed by Lettuce.
 *
 * <p>Every state transition runs as a single Lua script, so atomicity comes from
 * Redis script execution rather than from watch/retry loops. No Spring
 * dependencies — only a {@link StatefulRedisConnection} is required.
 *
 * <h2>Connection</h2>
 * <p>The connection must use {@link #CODEC}: response bodies are arbitrary binary,
 * so values are read and written as raw bytes rather than strings.
 *
 * <pre>{@code
 * RedisClient client = RedisClient.create("redis://localhost:6379");
 * StatefulRedisConnection<String, byte[]> connection =
 *         client.connect(RedisIdempotencyStore.CODEC);
 * IdempotencyStore store = new RedisIdempotencyStore(connection);
 * }</pre>
 *
 * <p>The caller owns the connection and is responsible for closing it. Lettuce
 * connections are thread-safe, so a single connection serves the whole
 * application — no pool is needed.
 *
 * <h2>Data model</h2>
 * <ul>
 *   <li>{@code <prefix>rec:<key>} — a hash holding the record's status, expiry
 *       timestamps, request fingerprint, and (once COMPLETE) the stored response.</li>
 *   <li>{@code <prefix>idx} — a sorted set whose score is the earliest instant the
 *       record can become purgeable, so {@link #purgeExpired()} reads only the
 *       expired slice instead of scanning the keyspace.</li>
 * </ul>
 *
 * <h2>Expiry</h2>
 * <p>The stored timestamps are authoritative for every read path, matching the
 * JDBC and in-memory stores exactly. Each write additionally sets a native Redis
 * TTL of {@code purgeableAt + retentionGrace}, so memory is still reclaimed if
 * {@link #purgeExpired()} is never called — the native TTL trails logical expiry
 * rather than racing it.
 *
 * <h2>Limitations</h2>
 * <p>Standalone and Sentinel topologies only. Redis Cluster is not supported: the
 * record keys and the shared index would hash to different slots.
 *
 * <p>Timestamps come from this store's {@link Clock}, not from Redis. As with the
 * JDBC store, clocks across application instances should be kept in sync (NTP);
 * skew shifts when a lock is considered stale.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    /**
     * The codec a connection passed to this store must be opened with: UTF-8 keys
     * and raw byte values, so binary response bodies survive a round trip intact.
     */
    public static final RedisCodec<String, byte[]> CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    private static final String DEFAULT_KEY_PREFIX = "idempotency:";
    private static final long DEFAULT_POLL_INTERVAL_MS = 50;
    private static final Duration DEFAULT_RETENTION_GRACE = Duration.ofHours(1);
    private static final int DEFAULT_PURGE_BATCH_SIZE = 500;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, List<String>>> HEADERS_TYPE = new TypeReference<>() {};

    /**
     * One atomic acquisition attempt.
     *
     * <p>A missing record, an expired COMPLETE record, a FAILED record, and an
     * IN_PROGRESS record whose lock has expired all fall through to the same
     * overwrite — the caller cannot tell a fresh key from a reclaimed one, which
     * is exactly the SPI contract.
     *
     * <p>KEYS: record, index. ARGV: now, lockExpiresAt, expiresAt, purgeableAt,
     * pexpireMs, lockTimeoutMs, fingerprint.
     */
    private static final LuaScript ACQUIRE = LuaScript.of(
            """
            local rec = KEYS[1]
            local now = tonumber(ARGV[1])
            local status = redis.call('HGET', rec, 'status')
            if status == 'COMPLETE' then
                if tonumber(redis.call('HGET', rec, 'expiresAt') or '0') > now then
                    local storedFingerprint = redis.call('HGET', rec, 'fingerprint') or ''
                    if storedFingerprint ~= ARGV[7] then
                        return {'MISMATCH', storedFingerprint}
                    end
                    return {'DUPLICATE',
                            redis.call('HGET', rec, 'code') or '0',
                            redis.call('HGET', rec, 'headers') or '',
                            redis.call('HGET', rec, 'body') or '',
                            redis.call('HGET', rec, 'completedAt') or '0'}
                end
            elseif status == 'IN_PROGRESS' then
                if tonumber(redis.call('HGET', rec, 'lockExpiresAt') or '0') > now then
                    return {'BUSY'}
                end
            end
            redis.call('DEL', rec)
            redis.call('HSET', rec,
                'status', 'IN_PROGRESS',
                'lockExpiresAt', ARGV[2],
                'expiresAt', ARGV[3],
                'lockTimeoutMs', ARGV[6],
                'fingerprint', ARGV[7])
            redis.call('ZADD', KEYS[2], ARGV[4], rec)
            redis.call('PEXPIRE', rec, ARGV[5])
            return {'ACQUIRED'}
            """);

    /** KEYS: record, index. ARGV: expiresAt, pexpireMs, code, headers, body, completedAt. */
    private static final LuaScript COMPLETE = LuaScript.of(
            """
            local rec = KEYS[1]
            local status = redis.call('HGET', rec, 'status')
            if not status then return {'MISSING'} end
            if status ~= 'IN_PROGRESS' then return {'CONFLICT', status} end
            redis.call('HSET', rec,
                'status', 'COMPLETE',
                'expiresAt', ARGV[1],
                'code', ARGV[3],
                'headers', ARGV[4],
                'body', ARGV[5],
                'completedAt', ARGV[6])
            redis.call('HDEL', rec, 'lockExpiresAt')
            redis.call('ZADD', KEYS[2], ARGV[1], rec)
            redis.call('PEXPIRE', rec, ARGV[2])
            return {'OK'}
            """);

    /**
     * KEYS: record, index. ARGV: now, graceMs.
     *
     * <p>A FAILED record expires after the original lock timeout rather than the
     * full TTL — it is immediately re-acquirable, so holding it for the whole TTL
     * would retain the response-free husk for nothing.
     */
    private static final LuaScript RELEASE = LuaScript.of(
            """
            local rec = KEYS[1]
            local status = redis.call('HGET', rec, 'status')
            if not status then return {'MISSING'} end
            if status ~= 'IN_PROGRESS' then return {'CONFLICT', status} end
            local lockTimeout = tonumber(redis.call('HGET', rec, 'lockTimeoutMs') or '0')
            local expiresAt = string.format('%d', tonumber(ARGV[1]) + lockTimeout)
            redis.call('HSET', rec, 'status', 'FAILED', 'expiresAt', expiresAt)
            redis.call('HDEL', rec, 'lockExpiresAt', 'code', 'headers', 'body', 'completedAt')
            redis.call('ZADD', KEYS[2], expiresAt, rec)
            redis.call('PEXPIRE', rec, string.format('%d', lockTimeout + tonumber(ARGV[2])))
            return {'OK'}
            """);

    /** KEYS: record, index. ARGV: now, newLockExpiresAt, graceMs. */
    private static final LuaScript EXTEND_LOCK = LuaScript.of(
            """
            local rec = KEYS[1]
            if redis.call('HGET', rec, 'status') ~= 'IN_PROGRESS' then return {'NOOP'} end
            local purgeableAt = tonumber(ARGV[2])
            local expiresAt = tonumber(redis.call('HGET', rec, 'expiresAt') or '0')
            if expiresAt > purgeableAt then purgeableAt = expiresAt end
            redis.call('HSET', rec, 'lockExpiresAt', ARGV[2])
            redis.call('ZADD', KEYS[2], string.format('%d', purgeableAt), rec)
            local pexpire = purgeableAt - tonumber(ARGV[1]) + tonumber(ARGV[3])
            if pexpire < 1 then pexpire = 1 end
            if redis.call('PTTL', rec) < pexpire then
                redis.call('PEXPIRE', rec, string.format('%d', pexpire))
            end
            return {'OK'}
            """);

    /**
     * One purge batch.
     *
     * <p>Index members whose record is already gone (reclaimed by the native TTL)
     * are dropped without being counted. Members that turn out not to be eligible
     * are re-scored, which cannot loop forever: a record is eligible exactly when
     * its {@code purgeableAt} has passed, so any re-score writes a future score.
     *
     * <p>KEYS: index. ARGV: now, batchSize. Returns {deleted, examined}.
     */
    private static final LuaScript PURGE = LuaScript.of(
            """
            local idx = KEYS[1]
            local now = tonumber(ARGV[1])
            local members = redis.call('ZRANGEBYSCORE', idx, '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            local deleted = 0
            for i = 1, #members do
                local rec = members[i]
                local status = redis.call('HGET', rec, 'status')
                if not status then
                    redis.call('ZREM', idx, rec)
                else
                    local purgeableAt = tonumber(redis.call('HGET', rec, 'expiresAt') or '0')
                    if status == 'IN_PROGRESS' then
                        local lockExpiresAt = tonumber(redis.call('HGET', rec, 'lockExpiresAt') or '0')
                        if lockExpiresAt > purgeableAt then purgeableAt = lockExpiresAt end
                    end
                    if purgeableAt <= now then
                        redis.call('DEL', rec)
                        redis.call('ZREM', idx, rec)
                        deleted = deleted + 1
                    else
                        redis.call('ZADD', idx, string.format('%d', purgeableAt), rec)
                    end
                end
            end
            return {deleted, #members}
            """);

    private final RedisCommands<String, byte[]> commands;
    private final String recordKeyPrefix;
    private final String indexKey;
    private final long pollIntervalMs;
    private final long graceMs;
    private final int purgeBatchSize;
    private final Clock clock;

    public RedisIdempotencyStore(StatefulRedisConnection<String, byte[]> connection) {
        this(connection, DEFAULT_KEY_PREFIX);
    }

    public RedisIdempotencyStore(StatefulRedisConnection<String, byte[]> connection, String keyPrefix) {
        this(
                connection,
                keyPrefix,
                DEFAULT_POLL_INTERVAL_MS,
                DEFAULT_RETENTION_GRACE,
                DEFAULT_PURGE_BATCH_SIZE,
                Clock.systemUTC());
    }

    /**
     * @param connection     an open Lettuce connection using {@link #CODEC}; the caller
     *                       retains ownership and must close it
     * @param keyPrefix      namespace for every key this store writes, allowing several
     *                       applications to share one Redis database
     * @param pollIntervalMs how long a caller waiting on an in-flight key sleeps between
     *                       acquisition attempts
     * @param retentionGrace how far the native Redis TTL trails logical expiry; the
     *                       backstop that reclaims memory when {@link #purgeExpired()}
     *                       is not scheduled
     * @param purgeBatchSize how many index entries {@link #purgeExpired()} examines per
     *                       round trip
     * @param clock          source of time for every expiry decision
     */
    public RedisIdempotencyStore(
            StatefulRedisConnection<String, byte[]> connection,
            String keyPrefix,
            long pollIntervalMs,
            Duration retentionGrace,
            int purgeBatchSize,
            Clock clock) {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        Objects.requireNonNull(retentionGrace, "retentionGrace must not be null");
        if (keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive, got: " + pollIntervalMs);
        }
        if (retentionGrace.isNegative()) {
            throw new IllegalArgumentException("retentionGrace must not be negative, got: " + retentionGrace);
        }
        if (purgeBatchSize <= 0) {
            throw new IllegalArgumentException("purgeBatchSize must be positive, got: " + purgeBatchSize);
        }
        this.commands = connection.sync();
        this.recordKeyPrefix = keyPrefix + "rec:";
        this.indexKey = keyPrefix + "idx";
        this.pollIntervalMs = pollIntervalMs;
        this.graceMs = retentionGrace.toMillis();
        this.purgeBatchSize = purgeBatchSize;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public AcquireResult tryAcquire(IdempotencyContext context) {
        Instant deadline = clock.instant().plus(context.lockTimeout());

        while (true) {
            AcquireResult result = attemptAcquire(context);
            if (result != null) {
                return result;
            }

            // Key is held by a live lock — sleep and try again until our deadline
            if (!clock.instant().isBefore(deadline)) {
                return AcquireResult.lockTimeout(context.key());
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AcquireResult.lockTimeout(context.key());
            }
        }
    }

    @Override
    public void complete(String key, StoredResponse response, Duration ttl) {
        long now = clock.millis();
        long expiresAt = now + ttl.toMillis();
        List<Object> reply = eval(
                COMPLETE,
                "complete key '" + key + "'",
                keysFor(key),
                arg(expiresAt),
                arg(pexpireMs(now, expiresAt)),
                arg(response.statusCode()),
                headersToJson(response.headers()),
                response.body(),
                arg(response.completedAt().toEpochMilli()));
        requireOk(reply, key, "complete");
    }

    @Override
    public void release(String key) {
        List<Object> reply =
                eval(RELEASE, "release key '" + key + "'", keysFor(key), arg(clock.millis()), arg(graceMs));
        requireOk(reply, key, "release");
    }

    @Override
    public void extendLock(String key, Duration extension) {
        long now = clock.millis();
        // Returns NOOP for unknown or non-IN_PROGRESS keys — the heartbeat may fire
        // after the key has already been completed or released.
        eval(
                EXTEND_LOCK,
                "extend lock for key '" + key + "'",
                keysFor(key),
                arg(now),
                arg(now + extension.toMillis()),
                arg(graceMs));
    }

    /**
     * Deletes expired records by walking the expiry index, in batches of
     * {@code purgeBatchSize}.
     *
     * <p>COMPLETE and FAILED records are removed once their {@code expiresAt} has
     * passed; IN_PROGRESS records only once <em>both</em> {@code lockExpiresAt} and
     * {@code expiresAt} have passed, since a record whose lock alone has expired is
     * still stealable by the next {@link #tryAcquire} caller.
     *
     * <p>Records whose native TTL already reclaimed them are not counted — the
     * return value reflects records this call actually deleted.
     *
     * @return the number of records deleted
     */
    @Override
    public int purgeExpired() {
        int total = 0;
        while (true) {
            List<Object> reply = eval(
                    PURGE, "purge expired records", new String[] {indexKey}, arg(clock.millis()), arg(purgeBatchSize));
            total += (int) number(reply.get(0));
            if (number(reply.get(1)) < purgeBatchSize) {
                return total;
            }
        }
    }

    /**
     * Runs one acquisition attempt.
     *
     * @return the resolved outcome, or {@code null} if the key is held by a live
     *     lock and the caller should poll again
     */
    private AcquireResult attemptAcquire(IdempotencyContext context) {
        long now = clock.millis();
        long lockExpiresAt = now + context.lockTimeout().toMillis();
        long expiresAt = now + context.ttl().toMillis();
        long purgeableAt = Math.max(lockExpiresAt, expiresAt);

        List<Object> reply = eval(
                ACQUIRE,
                "acquire key '" + context.key() + "'",
                keysFor(context.key()),
                arg(now),
                arg(lockExpiresAt),
                arg(expiresAt),
                arg(purgeableAt),
                arg(pexpireMs(now, purgeableAt)),
                arg(context.lockTimeout().toMillis()),
                arg(context.requestFingerprint()));

        String outcome = token(reply.get(0));
        return switch (outcome) {
            case "ACQUIRED" -> AcquireResult.acquired();
            case "BUSY" -> null;
            case "MISMATCH" -> AcquireResult.fingerprintMismatch(token(reply.get(1)), context.requestFingerprint());
            case "DUPLICATE" -> AcquireResult.duplicate(readResponse(reply));
            default -> throw new IdempotencyStoreException(
                    "Unexpected acquire outcome '" + outcome + "' for key '" + context.key() + "'");
        };
    }

    private StoredResponse readResponse(List<Object> reply) {
        int statusCode = Integer.parseInt(token(reply.get(1)));
        Map<String, List<String>> headers = jsonToHeaders(bytes(reply.get(2)));
        byte[] body = bytes(reply.get(3));
        Instant completedAt = Instant.ofEpochMilli(Long.parseLong(token(reply.get(4))));
        return new StoredResponse(statusCode, headers, body, completedAt);
    }

    /**
     * Translates a {@code complete} or {@code release} reply into the SPI's error
     * contract. Unlike a SQL {@code UPDATE ... WHERE status = 'IN_PROGRESS'}, the
     * script reports the offending status from inside the same atomic execution, so
     * the message is exact rather than a best-effort follow-up read.
     */
    private void requireOk(List<Object> reply, String key, String operation) {
        String outcome = token(reply.get(0));
        switch (outcome) {
            case "OK" -> {}
            case "MISSING" -> throw new IdempotencyStoreException(
                    "Cannot " + operation + " key '" + key + "': no entry exists. Was tryAcquire called?");
            case "CONFLICT" -> throw new IdempotencyStoreException("Cannot " + operation + " key '" + key
                    + "': entry is " + token(reply.get(1)) + ", expected IN_PROGRESS");
            default -> throw new IdempotencyStoreException(
                    "Unexpected " + operation + " outcome '" + outcome + "' for key '" + key + "'");
        }
    }

    /**
     * Runs a script by digest, falling back to {@code EVAL} when Redis has not cached
     * it yet — which also repopulates the cache for subsequent calls.
     */
    @SuppressWarnings("unchecked")
    private List<Object> eval(LuaScript script, String description, String[] keys, byte[]... args) {
        try {
            try {
                return (List<Object>) commands.evalsha(script.digest(), ScriptOutputType.MULTI, keys, args);
            } catch (RedisNoScriptException e) {
                return (List<Object>) commands.eval(script.body(), ScriptOutputType.MULTI, keys, args);
            }
        } catch (RedisException e) {
            throw new IdempotencyStoreException("Failed to " + description, e);
        }
    }

    private String[] keysFor(String key) {
        return new String[] {recordKeyPrefix + key, indexKey};
    }

    /** How long the record should physically survive: until it is purgeable, plus the grace. */
    private long pexpireMs(long now, long purgeableAt) {
        return Math.max(1, purgeableAt - now + graceMs);
    }

    private static byte[] arg(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] arg(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String token(Object element) {
        return new String(bytes(element), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(Object element) {
        return (byte[]) element;
    }

    private static long number(Object element) {
        return ((Number) element).longValue();
    }

    // --- JSON serialization ---

    static byte[] headersToJson(Map<String, List<String>> headers) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(headers);
        } catch (IOException e) {
            throw new IdempotencyStoreException("Failed to serialize response headers to JSON", e);
        }
    }

    static Map<String, List<String>> jsonToHeaders(byte[] json) {
        if (json == null || json.length == 0) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, HEADERS_TYPE);
        } catch (IOException e) {
            throw new IdempotencyStoreException("Failed to deserialize response headers from JSON", e);
        }
    }
}
