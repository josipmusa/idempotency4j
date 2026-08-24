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
import io.github.josipmusa.idempotency.core.exception.IdempotencyCorruptRecordException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyDurabilityException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyForeignRecordException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreUnavailableException;
import io.github.josipmusa.idempotency.test.IdempotencyStoreContract;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.protocol.CommandType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
                .isInstanceOf(IdempotencyStoreUnavailableException.class);
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
        assertThat(commands.exists("idempotency4j:rec:prefixed-key")).isZero();
    }

    @Test
    void When_RecordWritten_Expect_OwnershipAndFormatMarkersPresent() {
        acquire(store(), contextFor("owned-record"));

        Map<String, byte[]> record = commands.hgetall("idempotency4j:rec:owned-record");
        assertThat(new String(record.get("owner"), StandardCharsets.UTF_8))
                .isEqualTo(RedisIdempotencyStore.RECORD_OWNER);
        assertThat(new String(record.get("formatVersion"), StandardCharsets.UTF_8))
                .isEqualTo(RedisIdempotencyStore.FORMAT_VERSION);
    }

    @Test
    void When_ForeignStringUsesRecordKey_Expect_AcquireFailsWithoutMutation() {
        String redisKey = "idempotency4j:rec:foreign-string";
        byte[] original = "another application".getBytes(StandardCharsets.UTF_8);
        commands.set(redisKey, original);

        assertThatThrownBy(() -> store().tryAcquire(contextFor("foreign-string")))
                .isInstanceOf(IdempotencyForeignRecordException.class);

        assertThat(commands.get(redisKey)).isEqualTo(original);
    }

    @Test
    void When_ForeignRecordsMatchScanPattern_Expect_PurgeSkipsThem() {
        String foreignString = "shared:rec:foreign-string";
        String foreignHash = "shared:rec:foreign-hash";
        String unrelatedIndex = "shared:idx";
        commands.set(foreignString, "value".getBytes(StandardCharsets.UTF_8));
        commands.hset(foreignHash, "status", "COMPLETE".getBytes(StandardCharsets.UTF_8));
        commands.zadd(unrelatedIndex, 1, "member".getBytes(StandardCharsets.UTF_8));
        IdempotencyStore s = new RedisIdempotencyStore(connection, "shared:");

        assertThat(s.purgeExpired()).isZero();

        assertThat(commands.exists(foreignString, foreignHash, unrelatedIndex)).isEqualTo(3);
    }

    @Test
    void When_OwnedRecordUsesUnsupportedFormat_Expect_FailsClosed() {
        String redisKey = "idempotency4j:rec:future-format";
        commands.hset(redisKey, "owner", RedisIdempotencyStore.RECORD_OWNER.getBytes(StandardCharsets.UTF_8));
        commands.hset(redisKey, "formatVersion", "999".getBytes(StandardCharsets.US_ASCII));
        commands.hset(redisKey, "status", "FAILED".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> store().tryAcquire(contextFor("future-format")))
                .isInstanceOf(IdempotencyCorruptRecordException.class)
                .hasMessageContaining("formatVersion");

        assertThat(commands.hget(redisKey, "formatVersion")).isEqualTo("999".getBytes(StandardCharsets.US_ASCII));
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
        assertThat(commands.pttl("idempotency4j:rec:native-ttl-key")).isGreaterThan(ttl.toMillis());
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

        assertThat(commands.exists("idempotency4j:rec:purge-orphan-key")).isZero();
    }

    @Test
    void When_RecordReclaimedByNativeTtl_Expect_NoMetadataRemains() throws InterruptedException {
        IdempotencyStore s = new RedisIdempotencyStore(
                connection,
                RedisIdempotencyStoreConfig.builder()
                        .keyPrefix("ttl-only:")
                        .retentionGrace(Duration.ZERO)
                        .purgeBatchSize(100)
                        .build());
        acquire(s, context("orphan-index-key", Duration.ofMillis(10), Duration.ofMillis(2)));

        Thread.sleep(50);

        assertThat(s.purgeExpired())
                .as("A record already reclaimed by Redis is not counted as a purge deletion")
                .isZero();
        assertThat(commands.keys("ttl-only:*")).isEmpty();
    }

    @Test
    void When_PurgeBacklogExceedsBatchSize_Expect_AllRecordsRemoved() throws InterruptedException {
        IdempotencyStore s = new RedisIdempotencyStore(
                connection,
                RedisIdempotencyStoreConfig.builder().purgeBatchSize(3).build());
        for (int i = 0; i < 10; i++) {
            acquire(s, context("batched-key-" + i, Duration.ofMillis(10), Duration.ofMillis(2)));
        }

        Thread.sleep(50);

        assertThat(s.purgeExpired()).isEqualTo(10);
        assertThat(commands.keys("idempotency4j:rec:*")).isEmpty();
    }

    @Test
    void When_PurgeWorkBounded_Expect_SubsequentCallsResumeCursor() throws InterruptedException {
        IdempotencyStore s = new RedisIdempotencyStore(
                connection,
                RedisIdempotencyStoreConfig.builder()
                        .keyPrefix("bounded:")
                        .purgeBatchSize(3)
                        .maxPurgePagesPerCall(1)
                        .build());
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
        assertThat(commands.exists("idempotency4j:rec:extend-rescore-key")).isEqualTo(1);
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
        String record = "idempotency4j:rec:malformed-response";
        commands.hset(record, "owner", RedisIdempotencyStore.RECORD_OWNER.getBytes(StandardCharsets.UTF_8));
        commands.hset(record, "formatVersion", RedisIdempotencyStore.FORMAT_VERSION.getBytes(StandardCharsets.UTF_8));
        commands.hset(record, "status", "COMPLETE".getBytes(StandardCharsets.UTF_8));
        commands.hset(record, "expiresAt", Long.toString(future).getBytes(StandardCharsets.US_ASCII));
        commands.hset(record, "fingerprint", FINGERPRINT_DEFAULT.getBytes(StandardCharsets.UTF_8));
        commands.hset(record, "code", "not-a-number".getBytes(StandardCharsets.US_ASCII));
        commands.hset(record, "headers", "{}".getBytes(StandardCharsets.UTF_8));
        commands.hset(record, "body", new byte[0]);
        commands.hset(record, "completedAt", "0".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> store().tryAcquire(contextFor("malformed-response")))
                .isInstanceOf(IdempotencyCorruptRecordException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void When_KeyBusy_Expect_MonotonicWaitTimeout() {
        IdempotencyStore s = new RedisIdempotencyStore(connection, "monotonic:");
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
                connection,
                RedisIdempotencyStoreConfig.builder()
                        .keyPrefix("replicated:")
                        .replicaAcknowledgement(RedisReplicaAcknowledgement.require(1, Duration.ofMillis(10)))
                        .build());

        assertThatThrownBy(() -> s.tryAcquire(contextFor("no-replica")))
                .isInstanceOf(IdempotencyDurabilityException.class)
                .hasMessageContaining("required replicas");
    }

    @Test
    void When_CompleteReplicaAcknowledgementFails_Expect_WriteRemainsObservable() {
        String key = "uncertain-complete";
        IdempotencyStore normal = new RedisIdempotencyStore(connection, "uncertain:");
        var acquired = (AcquireResult.Acquired) normal.tryAcquire(contextFor(key));
        IdempotencyStore acknowledged = new RedisIdempotencyStore(
                connection,
                RedisIdempotencyStoreConfig.builder()
                        .keyPrefix("uncertain:")
                        .replicaAcknowledgement(RedisReplicaAcknowledgement.require(1, Duration.ofMillis(10)))
                        .build());
        StoredResponse response = new StoredResponse(201, Map.of(), "saved".getBytes(), Instant.now());

        assertThatThrownBy(() -> acknowledged.complete(key, acquired.leaseId(), response, Duration.ofHours(1)))
                .isInstanceOf(IdempotencyDurabilityException.class);

        AcquireResult retry = normal.tryAcquire(contextFor(key));
        assertThat(retry).isInstanceOf(AcquireResult.Duplicate.class);
        assertThat(((AcquireResult.Duplicate) retry).response().body()).isEqualTo("saved".getBytes());
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
                    RedisIdempotencyStoreConfig.builder()
                            .keyPrefix("replicated:")
                            .replicaAcknowledgement(RedisReplicaAcknowledgement.require(1, Duration.ofSeconds(1)))
                            .build());

            assertThat(s.tryAcquire(contextFor("with-replica"))).isInstanceOf(AcquireResult.Acquired.class);
        }
    }

    @Test
    void When_ConnectionUsesDocumentedRestrictedAcl_Expect_FullLifecycleSucceeds() {
        String username = "idempotency4j-test";
        String password = "test-password";
        AclSetuserArgs acl = new AclSetuserArgs()
                .reset()
                .on()
                .addPassword(password)
                .keyPattern("acl-safe:*")
                .addCommand(CommandType.EVAL)
                .addCommand(CommandType.EVALSHA)
                .addCommand(CommandType.SCAN)
                .addCommand(CommandType.WAIT)
                .addCommand(CommandType.TYPE)
                .addCommand(CommandType.TIME)
                .addCommand(CommandType.HGET)
                .addCommand(CommandType.HSET)
                .addCommand(CommandType.HDEL)
                .addCommand(CommandType.DEL)
                .addCommand(CommandType.PEXPIRE)
                .addCommand(CommandType.PTTL);
        commands.aclSetuser(username, acl);
        RedisURI restrictedUri = RedisURI.builder()
                .withHost(REDIS.getHost())
                .withPort(REDIS.getMappedPort(REDIS_PORT))
                .withAuthentication(username, password.toCharArray())
                .build();
        RedisClient restrictedClient = RedisClient.create(restrictedUri);

        try (StatefulRedisConnection<String, byte[]> restrictedConnection =
                restrictedClient.connect(RedisIdempotencyStore.CODEC)) {
            IdempotencyStore restrictedStore = new RedisIdempotencyStore(restrictedConnection, "acl-safe:");
            String key = "lifecycle";
            var acquired = (AcquireResult.Acquired) restrictedStore.tryAcquire(contextFor(key));
            restrictedStore.complete(
                    key,
                    acquired.leaseId(),
                    new StoredResponse(200, Map.of(), "ok".getBytes(), Instant.now()),
                    Duration.ofMillis(10));

            assertThat(restrictedStore.tryAcquire(contextFor(key))).isInstanceOf(AcquireResult.Duplicate.class);
            assertThatCode(restrictedStore::purgeExpired).doesNotThrowAnyException();
        } finally {
            restrictedClient.shutdown();
            commands.aclDeluser(username);
        }
    }

    @Test
    void When_InvalidConstructorArguments_Expect_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new RedisIdempotencyStore(connection, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyPrefix");
        assertThatThrownBy(() -> RedisIdempotencyStoreConfig.builder()
                        .pollInterval(Duration.ZERO)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pollInterval");
        assertThatThrownBy(() -> RedisIdempotencyStoreConfig.builder()
                        .retentionGrace(Duration.ofHours(-1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retentionGrace");
        assertThatThrownBy(() ->
                        RedisIdempotencyStoreConfig.builder().purgeBatchSize(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purgeBatchSize");
        assertThatThrownBy(() -> RedisReplicaAcknowledgement.require(-1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiredReplicas");
        assertThatThrownBy(() -> RedisReplicaAcknowledgement.require(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1ms");
        assertThatThrownBy(() -> RedisReplicaAcknowledgement.require(1, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1ms");
    }

    @Test
    void When_NullConnection_Expect_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new RedisIdempotencyStore(null)).isInstanceOf(NullPointerException.class);
    }
}
