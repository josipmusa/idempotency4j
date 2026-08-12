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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.StoredResponse;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreException;
import io.github.josipmusa.idempotency.test.IdempotencyStoreContract;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisIdempotencyStoreTest extends IdempotencyStoreContract {

    private static final int REDIS_PORT = 6379;
    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withNetwork(NETWORK)
            .withNetworkAliases("idempotency-redis-master")
            .withExposedPorts(REDIS_PORT);

    private static RedisClient client;
    private static StatefulRedisConnection<String, byte[]> connection;
    private static RedisCommands<String, byte[]> commands;

    @BeforeAll
    static void connect() {
        client = RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT)));
        connection = client.connect(RedisIdempotencyStore.CODEC);
        commands = connection.sync();
    }

    @AfterAll
    static void disconnect() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    @BeforeEach
    void flush() {
        commands.flushdb();
    }

    @Override
    protected IdempotencyStore store() {
        return new RedisIdempotencyStore(connection);
    }

    private IdempotencyContext context(String key, Duration ttl, Duration lockTimeout) {
        return new IdempotencyContext(key, ttl, lockTimeout, FINGERPRINT_DEFAULT);
    }

    @Test
    void When_ConnectionClosed_Expect_ThrowsIdempotencyStoreException() {
        StatefulRedisConnection<String, byte[]> closed = client.connect(RedisIdempotencyStore.CODEC);
        IdempotencyStore closedStore = new RedisIdempotencyStore(closed);
        closed.close();

        assertThatThrownBy(() -> acquire(closedStore, contextFor("unreachable-key")))
                .isInstanceOf(IdempotencyStoreException.class);
    }

    @Test
    void When_ScriptCacheFlushed_Expect_StoreRecovers() {
        IdempotencyStore s = store();
        acquire(s, contextFor("script-cache-key"));

        // Simulates a Redis restart: EVALSHA now fails with NOSCRIPT and the store
        // must fall back to EVAL rather than surfacing the error.
        commands.scriptFlush();

        assertThatCode(() -> complete(
                        s,
                        "script-cache-key",
                        new StoredResponse(200, Map.of(), "body".getBytes(), Instant.now()),
                        Duration.ofHours(1)))
                .doesNotThrowAnyException();
        assertThat(acquire(s, contextFor("script-cache-key"))).isInstanceOf(AcquireResult.Duplicate.class);
    }

    @Test
    void When_DigestComputedLocally_Expect_MatchesRedisScriptSha() {
        String body = "return {'PONG'}";

        // Guards the EVALSHA fast path: a wrong digest would make every call fall back
        // to EVAL, doubling the round trips without failing a single behavioral test.
        assertThat(LuaScript.of(body).digest()).isEqualTo(commands.scriptLoad(body));
    }

    @Test
    void When_CustomKeyPrefix_Expect_KeysNamespaced() {
        IdempotencyStore s = new RedisIdempotencyStore(connection, "tenant-a:");

        acquire(s, contextFor("prefixed-key"));

        assertThat(commands.exists("tenant-a:rec:prefixed-key")).isEqualTo(1);
        assertThat(commands.exists("tenant-a:idx")).isZero();
        assertThat(commands.exists("idempotency:rec:prefixed-key")).isZero();
    }

    @Test
    void When_SeparateKeyPrefixes_Expect_StoresDoNotShareKeys() {
        IdempotencyStore tenantA = new RedisIdempotencyStore(connection, "tenant-a:");
        IdempotencyStore tenantB = new RedisIdempotencyStore(connection, "tenant-b:");

        tenantA.tryAcquire(contextFor("shared-key"));

        assertThat(tenantB.tryAcquire(contextFor("shared-key"))).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_RecordWritten_Expect_NativeTtlOutlivesLogicalExpiry() {
        IdempotencyStore s = store();
        Duration ttl = Duration.ofSeconds(30);

        acquire(s, context("native-ttl-key", ttl, Duration.ofSeconds(5)));

        // The native TTL is the backstop that reclaims memory when purgeExpired() never
        // runs. It must trail logical expiry, otherwise Redis would delete records before
        // purgeExpired() could account for them.
        assertThat(commands.pttl("idempotency:rec:native-ttl-key")).isGreaterThan(ttl.toMillis());
    }

    @Test
    void When_ResponseBodyIsBinary_Expect_ReplayedByteForByte() {
        IdempotencyStore s = store();
        String key = "binary-body-key";
        byte[] binary = new byte[] {0, -1, -128, 127, 65, 0, -17, -69, -65};

        acquire(s, contextFor(key));
        complete(s, key, new StoredResponse(200, Map.of(), binary, Instant.now()), Duration.ofHours(1));

        AcquireResult result = acquire(s, contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
        assertThat(((AcquireResult.Duplicate) result).response().body()).isEqualTo(binary);
    }

    @Test
    void When_EmptyResponseBody_Expect_ReplayedAsEmpty() {
        IdempotencyStore s = store();
        String key = "empty-body-key";

        acquire(s, contextFor(key));
        complete(s, key, new StoredResponse(204, Map.of(), new byte[0], Instant.now()), Duration.ofHours(1));

        AcquireResult result = acquire(s, contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
        StoredResponse replayed = ((AcquireResult.Duplicate) result).response();
        assertThat(replayed.statusCode()).isEqualTo(204);
        assertThat(replayed.body()).isEmpty();
        assertThat(replayed.headers()).isEmpty();
    }

    @Test
    void When_PurgeRemovesRecord_Expect_RecordDeleted() throws InterruptedException {
        IdempotencyStore s = store();
        acquire(s, context("purge-orphan-key", Duration.ofMillis(10), Duration.ofMillis(2)));

        Thread.sleep(50);
        assertThat(s.purgeExpired()).isEqualTo(1);

        assertThat(commands.exists("idempotency:rec:purge-orphan-key")).isZero();
    }

    @Test
    void When_RecordReclaimedByNativeTtl_Expect_NoMetadataRemains() throws InterruptedException {
        IdempotencyStore s =
                new RedisIdempotencyStore(connection, "ttl-only:", 50, Duration.ZERO, 100, Clock.systemUTC());
        acquire(s, context("orphan-index-key", Duration.ofMillis(10), Duration.ofMillis(2)));

        Thread.sleep(50);

        assertThat(s.purgeExpired())
                .as("A record already reclaimed by Redis is not counted as a purge deletion")
                .isZero();
        assertThat(commands.keys("ttl-only:*")).isEmpty();
    }

    @Test
    void When_LegacyExpiryIndexExists_Expect_PurgeRemovesIt() {
        IdempotencyStore s = new RedisIdempotencyStore(connection, "legacy:");
        commands.zadd("legacy:idx", 1, "legacy:rec:old-key".getBytes(StandardCharsets.UTF_8));

        assertThat(s.purgeExpired()).isZero();

        assertThat(commands.exists("legacy:idx")).isZero();
    }

    @Test
    void When_PurgeBacklogExceedsBatchSize_Expect_AllRecordsRemoved() throws InterruptedException {
        IdempotencyStore s =
                new RedisIdempotencyStore(connection, "idempotency:", 50, Duration.ofHours(1), 3, Clock.systemUTC());
        for (int i = 0; i < 10; i++) {
            acquire(s, context("batched-key-" + i, Duration.ofMillis(10), Duration.ofMillis(2)));
        }

        Thread.sleep(50);

        assertThat(s.purgeExpired()).isEqualTo(10);
        assertThat(commands.keys("idempotency:rec:*")).isEmpty();
    }

    @Test
    void When_PurgeWorkBounded_Expect_SubsequentCallsResumeCursor() throws InterruptedException {
        IdempotencyStore s = new RedisIdempotencyStore(
                connection, "bounded:", 50, Duration.ofHours(1), 3, Clock.systemUTC(), 0, Duration.ZERO, 1);
        for (int i = 0; i < 100; i++) {
            acquire(s, context("bounded-key-" + i, Duration.ofMillis(10), Duration.ofMillis(2)));
        }
        Thread.sleep(50);

        int total = s.purgeExpired();
        assertThat(total).isLessThan(100);
        for (int i = 0; i < 200 && !commands.keys("bounded:rec:*").isEmpty(); i++) {
            total += s.purgeExpired();
        }

        assertThat(total).isEqualTo(100);
        assertThat(commands.keys("bounded:rec:*")).isEmpty();
    }

    @Test
    void When_PrefixContainsGlobCharacters_Expect_PurgeOnlyScansItsNamespace() throws InterruptedException {
        IdempotencyStore s = new RedisIdempotencyStore(connection, "tenant[*]?:\\");
        acquire(s, context("glob-key", Duration.ofMillis(10), Duration.ofMillis(2)));
        commands.set("tenantX:unrelated", "value".getBytes());

        Thread.sleep(50);

        assertThat(s.purgeExpired()).isEqualTo(1);
        assertThat(commands.exists("tenantX:unrelated")).isEqualTo(1);
    }

    @Test
    void When_ExtendLockCalled_Expect_PurgeKeepsRecord() throws InterruptedException {
        IdempotencyStore s = store();
        // TTL is shorter than the extended lock, so purge must use both timestamps.
        acquire(s, context("extend-rescore-key", Duration.ofMillis(10), Duration.ofMillis(10)));
        extendLock(s, "extend-rescore-key", Duration.ofSeconds(30));

        Thread.sleep(50);

        assertThat(s.purgeExpired()).isZero();
        assertThat(commands.exists("idempotency:rec:extend-rescore-key")).isEqualTo(1);
    }

    @Test
    void When_HeadersContainMultipleValues_Expect_AllReplayed() {
        IdempotencyStore s = store();
        String key = "multi-header-key";
        Map<String, List<String>> headers = Map.of("Set-Cookie", List.of("a=1", "b=2"), "X-Trace", List.of("t-1"));

        acquire(s, contextFor(key));
        complete(s, key, new StoredResponse(201, headers, "ok".getBytes(), Instant.now()), Duration.ofHours(1));

        AcquireResult result = acquire(s, contextFor(key));

        assertThat(((AcquireResult.Duplicate) result).response().headers()).isEqualTo(headers);
    }

    @Test
    void When_StoredResponseIsMalformed_Expect_StoreExceptionInsteadOfCodecFailure() {
        long future = Instant.now().plus(Duration.ofHours(1)).toEpochMilli();
        String record = "idempotency:rec:malformed-response";
        commands.hset(record, "status", "COMPLETE".getBytes(StandardCharsets.UTF_8));
        commands.hset(record, "expiresAt", Long.toString(future).getBytes(StandardCharsets.US_ASCII));
        commands.hset(record, "fingerprint", FINGERPRINT_DEFAULT.getBytes(StandardCharsets.UTF_8));
        commands.hset(record, "code", "not-a-number".getBytes(StandardCharsets.US_ASCII));
        commands.hset(record, "headers", "{}".getBytes(StandardCharsets.UTF_8));
        commands.hset(record, "body", new byte[0]);
        commands.hset(record, "completedAt", "0".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> store().tryAcquire(contextFor("malformed-response")))
                .isInstanceOf(IdempotencyStoreException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void When_CustomClockProvided_Expect_StoreConstructsSuccessfully() {
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);

        var store = new RedisIdempotencyStore(connection, "clock:", 50, Duration.ofHours(1), 100, fixedClock);

        assertThat(store).isNotNull();
    }

    @Test
    void When_ClockIsFixedAndKeyBusy_Expect_MonotonicWaitTimeout() {
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
        IdempotencyStore s = new RedisIdempotencyStore(connection, "fixed:", 50, Duration.ofHours(1), 100, fixedClock);
        acquire(s, context("fixed-busy", Duration.ofHours(1), Duration.ofSeconds(5)));

        long started = System.nanoTime();
        AcquireResult result = s.tryAcquire(context("fixed-busy", Duration.ofHours(1), Duration.ofMillis(20)));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(result).isInstanceOf(AcquireResult.LockTimeout.class);
        assertThat(elapsedMs).isBetween(20L, 250L);
    }

    @Test
    void When_HolderCompletesAfterWaitDeadline_Expect_WaiterStillTimesOut() throws Exception {
        IdempotencyStore s = store();
        String key = "deadline-boundary";
        var first = (AcquireResult.Acquired) s.tryAcquire(contextFor(key));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var completion = executor.submit(() -> {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                s.complete(
                        key,
                        first.leaseId(),
                        new StoredResponse(200, Map.of(), new byte[0], Instant.now()),
                        Duration.ofHours(1));
            });

            AcquireResult result = s.tryAcquire(context(key, Duration.ofHours(1), Duration.ofMillis(10)));

            assertThat(result).isInstanceOf(AcquireResult.LockTimeout.class);
            completion.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void When_ReplicaAcknowledgementRequiredButUnavailable_Expect_FailsClosed() {
        IdempotencyStore s = new RedisIdempotencyStore(
                connection, "replicated:", 50, Duration.ofHours(1), 100, Clock.systemUTC(), 1, Duration.ofMillis(10));

        assertThatThrownBy(() -> s.tryAcquire(contextFor("no-replica")))
                .isInstanceOf(IdempotencyStoreException.class)
                .hasMessageContaining("required replicas");
    }

    @Test
    void When_ReplicaIsConnected_Expect_RequiredAcknowledgementSucceeds() throws Exception {
        try (GenericContainer<?> replica = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withNetwork(NETWORK)
                .withCommand("redis-server", "--replicaof", "idempotency-redis-master", "6379")) {
            replica.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!commands.info("replication").contains("state=online")) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("Redis replica did not connect within 10 seconds");
                }
                Thread.sleep(25);
            }

            IdempotencyStore s = new RedisIdempotencyStore(
                    connection,
                    "replicated:",
                    50,
                    Duration.ofHours(1),
                    100,
                    Clock.systemUTC(),
                    1,
                    Duration.ofSeconds(1));

            assertThat(s.tryAcquire(contextFor("with-replica"))).isInstanceOf(AcquireResult.Acquired.class);
        }
    }

    @Test
    void When_InvalidConstructorArguments_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new RedisIdempotencyStore(connection, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyPrefix");
        assertThatThrownBy(() ->
                        new RedisIdempotencyStore(connection, "p:", 0, Duration.ofHours(1), 10, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pollIntervalMs");
        assertThatThrownBy(() ->
                        new RedisIdempotencyStore(connection, "p:", 50, Duration.ofHours(-1), 10, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retentionGrace");
        assertThatThrownBy(() ->
                        new RedisIdempotencyStore(connection, "p:", 50, Duration.ofHours(1), 0, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purgeBatchSize");
        assertThatThrownBy(() -> new RedisIdempotencyStore(
                        connection, "p:", 50, Duration.ofHours(1), 10, Clock.systemUTC(), -1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiredReplicaAcks");
        assertThatThrownBy(() -> new RedisIdempotencyStore(
                        connection, "p:", 50, Duration.ofHours(1), 10, Clock.systemUTC(), 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replicaAckTimeout");
    }

    @Test
    void When_NullConnection_Expect_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new RedisIdempotencyStore(null)).isInstanceOf(NullPointerException.class);
    }
}
