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
import io.github.josipmusa.idempotency.core.exception.IdempotencyCorruptRecordException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyDurabilityException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyForeignRecordException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreUnavailableException;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Redis implementation of {@link IdempotencyStore}, backed by Lettuce.
 *
 * <p>Every state transition runs as one Lua script. The scripts use Redis {@code TIME}, so lock
 * ownership and expiry decisions do not depend on application clock synchronization.
 *
 * <h2>Connection</h2>
 * <p>The connection must use {@link #CODEC}, because response bodies are arbitrary binary values.
 * The caller owns the connection and is responsible for closing it.
 *
 * <pre>{@code
 * RedisClient client = RedisClient.create("redis://localhost:6379");
 * StatefulRedisConnection<String, byte[]> connection =
 *         client.connect(RedisIdempotencyStore.CODEC);
 * RedisIdempotencyStoreConfig config = RedisIdempotencyStoreConfig.builder()
 *         .keyPrefix("orders:idempotency:")
 *         .build();
 * IdempotencyStore store = new RedisIdempotencyStore(connection, config);
 * }</pre>
 *
 * <h2>Namespace ownership</h2>
 * <p>Every record contains an owner and format marker. The store never overwrites or deletes data
 * without matching markers. A colliding foreign key fails closed, while purge skips it.
 *
 * <h2>Operational requirements</h2>
 * <p>Use Redis 7 or newer with persistence appropriate to the application and a {@code noeviction}
 * memory policy. An evicted live record can allow the protected operation to execute again.
 * Standalone and Sentinel deployments are supported. Redis Cluster is not supported.
 *
 * <p>Replica acknowledgement is disabled by default. When enabled, {@code WAIT} runs on the same
 * connection immediately after each mutation. A timeout means the primary may already contain the
 * mutation, so the store reports {@link IdempotencyDurabilityException} rather than implying that
 * the write failed.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    /** UTF-8 keys and raw byte values for lossless response storage. */
    public static final RedisCodec<String, byte[]> CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    static final String RECORD_OWNER = "idempotency4j";
    static final String FORMAT_VERSION = "1";

    private static final long MAX_POLL_INTERVAL_MS = 1_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, List<String>>> HEADERS_TYPE = new TypeReference<>() {};

    /** KEYS: record. ARGV: lock timeout, TTL, grace, fingerprint, lease, owner, format. */
    private static final LuaScript ACQUIRE = LuaScript.of(
            """
            local rec = KEYS[1]
            local kind = redis.call('TYPE', rec).ok
            local status = nil
            if kind ~= 'none' then
                if kind ~= 'hash' then return {'FOREIGN'} end
                if redis.call('HGET', rec, 'owner') ~= ARGV[6] then return {'FOREIGN'} end
                if redis.call('HGET', rec, 'formatVersion') ~= ARGV[7] then return {'CORRUPT', 'formatVersion'} end
                status = redis.call('HGET', rec, 'status')
                if not status then return {'CORRUPT', 'status'} end
            end

            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            if status == 'COMPLETE' then
                local expiresAt = tonumber(redis.call('HGET', rec, 'expiresAt'))
                local storedFingerprint = redis.call('HGET', rec, 'fingerprint')
                if not expiresAt or not storedFingerprint then return {'CORRUPT', 'complete fields'} end
                if expiresAt > now then
                    if storedFingerprint ~= ARGV[4] then return {'MISMATCH', storedFingerprint} end
                    return {'DUPLICATE',
                            redis.call('HGET', rec, 'code') or '',
                            redis.call('HGET', rec, 'headers') or '',
                            redis.call('HGET', rec, 'body') or '',
                            redis.call('HGET', rec, 'completedAt') or ''}
                end
            elseif status == 'IN_PROGRESS' then
                local lockExpiresAt = tonumber(redis.call('HGET', rec, 'lockExpiresAt'))
                if not lockExpiresAt then return {'CORRUPT', 'lockExpiresAt'} end
                if lockExpiresAt > now then return {'BUSY'} end
            elseif status ~= nil and status ~= 'FAILED' then
                return {'CORRUPT', 'status'}
            end

            local lockTimeout = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])
            local grace = tonumber(ARGV[3])
            local lockExpiresAt = now + lockTimeout
            local expiresAt = now + ttl
            local physicalTtl = math.max(lockTimeout, ttl) + grace
            redis.call('DEL', rec)
            redis.call('HSET', rec,
                'owner', ARGV[6],
                'formatVersion', ARGV[7],
                'status', 'IN_PROGRESS',
                'lockExpiresAt', string.format('%.0f', lockExpiresAt),
                'expiresAt', string.format('%.0f', expiresAt),
                'lockTimeoutMs', ARGV[1],
                'fingerprint', ARGV[4],
                'leaseId', ARGV[5])
            redis.call('PEXPIRE', rec, physicalTtl)
            return {'ACQUIRED'}
            """);

    /** KEYS: record. ARGV: lease, TTL, grace, code, headers, body, completed, owner, format. */
    private static final LuaScript COMPLETE = LuaScript.of(
            """
            local rec = KEYS[1]
            local kind = redis.call('TYPE', rec).ok
            if kind == 'none' then return {'MISSING'} end
            if kind ~= 'hash' or redis.call('HGET', rec, 'owner') ~= ARGV[8] then return {'FOREIGN'} end
            if redis.call('HGET', rec, 'formatVersion') ~= ARGV[9] then return {'CORRUPT', 'formatVersion'} end
            local status = redis.call('HGET', rec, 'status')
            if status ~= 'IN_PROGRESS' then return {'CONFLICT', status or 'missing'} end
            if redis.call('HGET', rec, 'leaseId') ~= ARGV[1] then return {'STALE'} end

            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            local ttl = tonumber(ARGV[2])
            local physicalTtl = ttl + tonumber(ARGV[3])
            redis.call('HSET', rec,
                'status', 'COMPLETE',
                'expiresAt', string.format('%.0f', now + ttl),
                'code', ARGV[4],
                'headers', ARGV[5],
                'body', ARGV[6],
                'completedAt', ARGV[7])
            redis.call('HDEL', rec, 'lockExpiresAt', 'leaseId', 'lockTimeoutMs')
            redis.call('PEXPIRE', rec, physicalTtl)
            return {'OK'}
            """);

    /** KEYS: record. ARGV: lease, grace, owner, format. */
    private static final LuaScript RELEASE = LuaScript.of(
            """
            local rec = KEYS[1]
            local kind = redis.call('TYPE', rec).ok
            if kind == 'none' then return {'MISSING'} end
            if kind ~= 'hash' or redis.call('HGET', rec, 'owner') ~= ARGV[3] then return {'FOREIGN'} end
            if redis.call('HGET', rec, 'formatVersion') ~= ARGV[4] then return {'CORRUPT', 'formatVersion'} end
            local status = redis.call('HGET', rec, 'status')
            if status ~= 'IN_PROGRESS' then return {'CONFLICT', status or 'missing'} end
            if redis.call('HGET', rec, 'leaseId') ~= ARGV[1] then return {'STALE'} end
            local lockTimeout = tonumber(redis.call('HGET', rec, 'lockTimeoutMs'))
            if not lockTimeout then return {'CORRUPT', 'lockTimeoutMs'} end

            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            redis.call('HSET', rec, 'status', 'FAILED', 'expiresAt', string.format('%.0f', now + lockTimeout))
            redis.call('HDEL', rec, 'lockExpiresAt', 'leaseId', 'lockTimeoutMs', 'code', 'headers', 'body', 'completedAt')
            redis.call('PEXPIRE', rec, lockTimeout + tonumber(ARGV[2]))
            return {'OK'}
            """);

    /** KEYS: record. ARGV: lease, extension, grace, owner, format. */
    private static final LuaScript EXTEND_LOCK = LuaScript.of(
            """
            local rec = KEYS[1]
            local kind = redis.call('TYPE', rec).ok
            if kind == 'none' then return {'NOOP'} end
            if kind ~= 'hash' or redis.call('HGET', rec, 'owner') ~= ARGV[4] then return {'FOREIGN'} end
            if redis.call('HGET', rec, 'formatVersion') ~= ARGV[5] then return {'CORRUPT', 'formatVersion'} end
            if redis.call('HGET', rec, 'status') ~= 'IN_PROGRESS' then return {'NOOP'} end
            if redis.call('HGET', rec, 'leaseId') ~= ARGV[1] then return {'NOOP'} end

            local expiresAt = tonumber(redis.call('HGET', rec, 'expiresAt'))
            if not expiresAt then return {'CORRUPT', 'expiresAt'} end
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            local newLockExpiresAt = now + tonumber(ARGV[2])
            local purgeableAt = math.max(expiresAt, newLockExpiresAt)
            local physicalTtl = math.max(1, purgeableAt - now + tonumber(ARGV[3]))
            redis.call('HSET', rec, 'lockExpiresAt', string.format('%.0f', newLockExpiresAt))
            if redis.call('PTTL', rec) < physicalTtl then redis.call('PEXPIRE', rec, physicalTtl) end
            return {'OK'}
            """);

    /** KEYS: records. ARGV: owner, format. Returns the number of owned records deleted. */
    private static final LuaScript PURGE = LuaScript.of(
            """
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            local deleted = 0
            for i = 1, #KEYS do
                local rec = KEYS[i]
                if redis.call('TYPE', rec).ok == 'hash'
                        and redis.call('HGET', rec, 'owner') == ARGV[1]
                        and redis.call('HGET', rec, 'formatVersion') == ARGV[2] then
                    local status = redis.call('HGET', rec, 'status')
                    local expiresAt = tonumber(redis.call('HGET', rec, 'expiresAt'))
                    local purgeableAt = expiresAt
                    if status == 'IN_PROGRESS' then
                        local lockExpiresAt = tonumber(redis.call('HGET', rec, 'lockExpiresAt'))
                        if lockExpiresAt and (not purgeableAt or lockExpiresAt > purgeableAt) then
                            purgeableAt = lockExpiresAt
                        end
                    end
                    if (status == 'COMPLETE' or status == 'FAILED' or status == 'IN_PROGRESS')
                            and purgeableAt and purgeableAt <= now then
                        redis.call('DEL', rec)
                        deleted = deleted + 1
                    end
                end
            end
            return {deleted}
            """);

    private final RedisCommands<String, byte[]> commands;
    private final String recordKeyPrefix;
    private final String recordScanPattern;
    private final long pollIntervalMs;
    private final long graceMs;
    private final int purgeBatchSize;
    private final int maxPurgePagesPerCall;
    private final RedisReplicaAcknowledgement replicaAcknowledgement;
    private String purgeCursor = "0";

    public RedisIdempotencyStore(StatefulRedisConnection<String, byte[]> connection) {
        this(connection, RedisIdempotencyStoreConfig.defaults());
    }

    /** Convenience constructor for callers that only need to customize the namespace. */
    public RedisIdempotencyStore(StatefulRedisConnection<String, byte[]> connection, String keyPrefix) {
        this(
                connection,
                RedisIdempotencyStoreConfig.builder().keyPrefix(keyPrefix).build());
    }

    public RedisIdempotencyStore(
            StatefulRedisConnection<String, byte[]> connection, RedisIdempotencyStoreConfig config) {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(config, "config must not be null");
        commands = connection.sync();
        recordKeyPrefix = config.keyPrefix() + "rec:";
        recordScanPattern = globEscape(recordKeyPrefix) + "*";
        pollIntervalMs = config.pollInterval().toMillis();
        graceMs = config.retentionGrace().toMillis();
        purgeBatchSize = config.purgeBatchSize();
        maxPurgePagesPerCall = config.maxPurgePagesPerCall();
        replicaAcknowledgement = config.replicaAcknowledgement();
    }

    @Override
    public AcquireResult tryAcquire(IdempotencyContext context) {
        Objects.requireNonNull(context, "context must not be null");
        long startedAtNanos = System.nanoTime();
        long timeoutNanos = context.lockTimeout().toNanos();
        String leaseId = UUID.randomUUID().toString();
        int busyAttempts = 0;
        boolean firstAttempt = true;

        while (true) {
            if (!firstAttempt && elapsedNanos(startedAtNanos) >= timeoutNanos) {
                return AcquireResult.lockTimeout(context.key());
            }
            firstAttempt = false;
            AcquireResult result = attemptAcquire(context, leaseId);
            if (result != null) {
                return result;
            }

            long remainingNanos = timeoutNanos - elapsedNanos(startedAtNanos);
            if (remainingNanos <= 0) {
                return AcquireResult.lockTimeout(context.key());
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, jitteredBackoffNanos(busyAttempts++)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AcquireResult.lockTimeout(context.key());
            }
        }
    }

    @Override
    public void complete(String key, String leaseId, StoredResponse response, Duration ttl) {
        Objects.requireNonNull(response, "response must not be null");
        requireMillisecondDuration(ttl, "ttl");
        List<Object> reply = eval(
                COMPLETE,
                "complete key '" + key + "'",
                keysFor(key),
                arg(leaseId),
                arg(ttl.toMillis()),
                arg(graceMs),
                arg(response.statusCode()),
                headersToJson(response.headers()),
                response.body(),
                arg(response.completedAt().toEpochMilli()),
                arg(RECORD_OWNER),
                arg(FORMAT_VERSION));
        requireOk(reply, key, "complete");
        awaitReplication("complete key '" + key + "'");
    }

    @Override
    public void release(String key, String leaseId) {
        List<Object> reply = eval(
                RELEASE,
                "release key '" + key + "'",
                keysFor(key),
                arg(leaseId),
                arg(graceMs),
                arg(RECORD_OWNER),
                arg(FORMAT_VERSION));
        requireOk(reply, key, "release");
        awaitReplication("release key '" + key + "'");
    }

    @Override
    public void extendLock(String key, String leaseId, Duration extension) {
        requireMillisecondDuration(extension, "extension");
        List<Object> reply = eval(
                EXTEND_LOCK,
                "extend lock for key '" + key + "'",
                keysFor(key),
                arg(leaseId),
                arg(extension.toMillis()),
                arg(graceMs),
                arg(RECORD_OWNER),
                arg(FORMAT_VERSION));
        String outcome = outcome(reply, key, "extend lock");
        switch (outcome) {
            case "OK" -> awaitReplication("extend lock for key '" + key + "'");
            case "NOOP" -> {}
            case "FOREIGN" -> throw foreignRecord(key);
            case "CORRUPT" -> throw corruptRecord(key, reply);
            default -> throw unexpectedOutcome(outcome, key, "extend lock");
        }
    }

    @Override
    public synchronized int purgeExpired() {
        long total = 0;
        ScanArgs scanArgs = ScanArgs.Builder.matches(recordScanPattern).limit(purgeBatchSize);
        int pages = 0;
        try {
            while (pages < maxPurgePagesPerCall) {
                KeyScanCursor<String> cursor = commands.scan(ScanCursor.of(purgeCursor), scanArgs);
                pages++;
                if (!cursor.getKeys().isEmpty()) {
                    String[] keys = cursor.getKeys().toArray(String[]::new);
                    List<Object> reply =
                            eval(PURGE, "purge expired records", keys, arg(RECORD_OWNER), arg(FORMAT_VERSION));
                    long deleted = number(reply, 0, "purge expired records");
                    total += deleted;
                    if (deleted > 0) {
                        awaitReplication("replicate purged records");
                    }
                }
                purgeCursor = cursor.isFinished() ? "0" : cursor.getCursor();
                if (cursor.isFinished()) {
                    break;
                }
            }
            return Math.toIntExact(total);
        } catch (IdempotencyStoreException e) {
            throw e;
        } catch (RedisException e) {
            throw new IdempotencyStoreUnavailableException("Failed to scan records for expiry", e);
        }
    }

    private AcquireResult attemptAcquire(IdempotencyContext context, String leaseId) {
        List<Object> reply = eval(
                ACQUIRE,
                "acquire key '" + context.key() + "'",
                keysFor(context.key()),
                arg(context.lockTimeout().toMillis()),
                arg(context.ttl().toMillis()),
                arg(graceMs),
                arg(context.requestFingerprint()),
                arg(leaseId),
                arg(RECORD_OWNER),
                arg(FORMAT_VERSION));

        String outcome = outcome(reply, context.key(), "acquire");
        return switch (outcome) {
            case "ACQUIRED" -> {
                awaitReplication("acquire key '" + context.key() + "'");
                yield AcquireResult.acquired(leaseId);
            }
            case "BUSY" -> null;
            case "MISMATCH" -> AcquireResult.fingerprintMismatch(
                    token(reply, 1, "acquire key '" + context.key() + "'"), context.requestFingerprint());
            case "DUPLICATE" -> AcquireResult.duplicate(readResponse(reply, context.key()));
            case "FOREIGN" -> throw foreignRecord(context.key());
            case "CORRUPT" -> throw corruptRecord(context.key(), reply);
            default -> throw unexpectedOutcome(outcome, context.key(), "acquire");
        };
    }

    private StoredResponse readResponse(List<Object> reply, String key) {
        try {
            int statusCode = Integer.parseInt(token(reply, 1, "read response"));
            Map<String, List<String>> headers = jsonToHeaders(bytes(reply, 2, "read response"));
            byte[] body = bytes(reply, 3, "read response");
            Instant completedAt = Instant.ofEpochMilli(Long.parseLong(token(reply, 4, "read response")));
            return new StoredResponse(statusCode, headers, body, completedAt);
        } catch (IdempotencyCorruptRecordException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IdempotencyCorruptRecordException(
                    "Stored Redis response for key '" + key + "' is malformed and cannot be replayed", e);
        }
    }

    private void requireOk(List<Object> reply, String key, String operation) {
        String result = outcome(reply, key, operation);
        switch (result) {
            case "OK" -> {}
            case "MISSING" -> throw new IdempotencyLeaseLostException(
                    "Cannot " + operation + " key '" + key + "': no entry exists or it expired");
            case "CONFLICT" -> throw new IdempotencyLeaseLostException("Cannot " + operation + " key '" + key
                    + "': entry is " + token(reply, 1, operation) + ", expected IN_PROGRESS");
            case "STALE" -> throw new IdempotencyLeaseLostException(
                    "Cannot " + operation + " key '" + key + "': lease no longer owns the key");
            case "FOREIGN" -> throw foreignRecord(key);
            case "CORRUPT" -> throw corruptRecord(key, reply);
            default -> throw unexpectedOutcome(result, key, operation);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> eval(LuaScript script, String description, String[] keys, byte[]... args) {
        try {
            try {
                return (List<Object>) commands.evalsha(script.digest(), ScriptOutputType.MULTI, keys, args);
            } catch (RedisNoScriptException e) {
                return (List<Object>) commands.eval(script.body(), ScriptOutputType.MULTI, keys, args);
            }
        } catch (RedisException e) {
            throw new IdempotencyStoreUnavailableException("Failed to " + description, e);
        }
    }

    private void awaitReplication(String description) {
        if (!replicaAcknowledgement.enabled()) {
            return;
        }
        try {
            long acknowledged = commands.waitForReplication(
                    replicaAcknowledgement.requiredReplicas(),
                    replicaAcknowledgement.timeout().toMillis());
            if (acknowledged < replicaAcknowledgement.requiredReplicas()) {
                throw new IdempotencyDurabilityException("Redis accepted the mutation to " + description + ", but "
                        + acknowledged + " of " + replicaAcknowledgement.requiredReplicas()
                        + " required replicas acknowledged it");
            }
        } catch (IdempotencyDurabilityException e) {
            throw e;
        } catch (RedisException e) {
            throw new IdempotencyDurabilityException(
                    "Redis accepted the mutation to " + description
                            + ", but replica acknowledgement could not be confirmed",
                    e);
        }
    }

    private long jitteredBackoffNanos(int busyAttempts) {
        int shift = Math.min(busyAttempts, 10);
        long upperMs = pollIntervalMs > (MAX_POLL_INTERVAL_MS >> shift)
                ? MAX_POLL_INTERVAL_MS
                : Math.min(MAX_POLL_INTERVAL_MS, pollIntervalMs << shift);
        long lowerMs = Math.max(1, upperMs / 2);
        return TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextLong(lowerMs, upperMs + 1));
    }

    private static long elapsedNanos(long startedAtNanos) {
        return System.nanoTime() - startedAtNanos;
    }

    private String[] keysFor(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return new String[] {recordKeyPrefix + key};
    }

    private static IdempotencyForeignRecordException foreignRecord(String key) {
        return new IdempotencyForeignRecordException(
                "Redis key for idempotency key '" + key + "' is not owned by idempotency4j");
    }

    private static IdempotencyCorruptRecordException corruptRecord(String key, List<Object> reply) {
        String field = reply.size() > 1 ? token(reply, 1, "read corrupt-record reason") : "record";
        return new IdempotencyCorruptRecordException(
                "Redis record for idempotency key '" + key + "' has an invalid or unsupported " + field);
    }

    private static IdempotencyCorruptRecordException unexpectedOutcome(String outcome, String key, String operation) {
        return new IdempotencyCorruptRecordException(
                "Unexpected Redis " + operation + " outcome '" + outcome + "' for key '" + key + "'");
    }

    private static String outcome(List<Object> reply, String key, String operation) {
        if (reply == null || reply.isEmpty()) {
            throw new IdempotencyCorruptRecordException(
                    "Redis returned an empty " + operation + " result for key '" + key + "'");
        }
        return token(reply, 0, operation);
    }

    private static String globEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("?", "\\?")
                .replace("[", "\\[");
    }

    private static void requireMillisecondDuration(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least 1ms, got: " + value);
        }
    }

    private static byte[] arg(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] arg(String value) {
        return Objects.requireNonNull(value, "Redis script argument must not be null")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String token(List<Object> reply, int index, String operation) {
        return new String(bytes(reply, index, operation), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(List<Object> reply, int index, String operation) {
        try {
            return (byte[]) reply.get(index);
        } catch (IndexOutOfBoundsException | ClassCastException | NullPointerException e) {
            throw new IdempotencyCorruptRecordException("Malformed Redis result while attempting to " + operation, e);
        }
    }

    private static long number(List<Object> reply, int index, String operation) {
        try {
            return ((Number) reply.get(index)).longValue();
        } catch (IndexOutOfBoundsException | ClassCastException | NullPointerException e) {
            throw new IdempotencyCorruptRecordException("Malformed Redis result while attempting to " + operation, e);
        }
    }

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
            throw new IdempotencyCorruptRecordException("Stored Redis response headers are malformed", e);
        }
    }
}
