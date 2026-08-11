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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisIdempotencyStoreTest extends IdempotencyStoreContract {

    private static final int REDIS_PORT = 6379;

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);

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

        assertThatThrownBy(() -> closedStore.tryAcquire(contextFor("unreachable-key")))
                .isInstanceOf(IdempotencyStoreException.class);
    }

    @Test
    void When_ScriptCacheFlushed_Expect_StoreRecovers() {
        IdempotencyStore s = store();
        s.tryAcquire(contextFor("script-cache-key"));

        // Simulates a Redis restart: EVALSHA now fails with NOSCRIPT and the store
        // must fall back to EVAL rather than surfacing the error.
        commands.scriptFlush();

        assertThatCode(() -> s.complete(
                        "script-cache-key",
                        new StoredResponse(200, Map.of(), "body".getBytes(), Instant.now()),
                        Duration.ofHours(1)))
                .doesNotThrowAnyException();
        assertThat(s.tryAcquire(contextFor("script-cache-key"))).isInstanceOf(AcquireResult.Duplicate.class);
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

        s.tryAcquire(contextFor("prefixed-key"));

        assertThat(commands.exists("tenant-a:rec:prefixed-key")).isEqualTo(1);
        assertThat(commands.zcard("tenant-a:idx")).isEqualTo(1);
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

        s.tryAcquire(context("native-ttl-key", ttl, Duration.ofSeconds(5)));

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

        s.tryAcquire(contextFor(key));
        s.complete(key, new StoredResponse(200, Map.of(), binary, Instant.now()), Duration.ofHours(1));

        AcquireResult result = s.tryAcquire(contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
        assertThat(((AcquireResult.Duplicate) result).response().body()).isEqualTo(binary);
    }

    @Test
    void When_EmptyResponseBody_Expect_ReplayedAsEmpty() {
        IdempotencyStore s = store();
        String key = "empty-body-key";

        s.tryAcquire(contextFor(key));
        s.complete(key, new StoredResponse(204, Map.of(), new byte[0], Instant.now()), Duration.ofHours(1));

        AcquireResult result = s.tryAcquire(contextFor(key));

        assertThat(result).isInstanceOf(AcquireResult.Duplicate.class);
        StoredResponse replayed = ((AcquireResult.Duplicate) result).response();
        assertThat(replayed.statusCode()).isEqualTo(204);
        assertThat(replayed.body()).isEmpty();
        assertThat(replayed.headers()).isEmpty();
    }

    @Test
    void When_PurgeRemovesRecord_Expect_IndexLeftWithoutOrphans() throws InterruptedException {
        IdempotencyStore s = store();
        s.tryAcquire(context("purge-orphan-key", Duration.ofMillis(10), Duration.ofMillis(2)));

        Thread.sleep(50);
        assertThat(s.purgeExpired()).isEqualTo(1);

        assertThat(commands.zcard("idempotency:idx")).isZero();
        assertThat(commands.exists("idempotency:rec:purge-orphan-key")).isZero();
    }

    @Test
    void When_RecordReclaimedByNativeTtl_Expect_PurgeDropsIndexEntryUncounted() throws InterruptedException {
        IdempotencyStore s = store();
        s.tryAcquire(context("orphan-index-key", Duration.ofMillis(10), Duration.ofMillis(2)));

        // Simulate the native TTL firing before the purge job ran
        commands.del("idempotency:rec:orphan-index-key");
        Thread.sleep(50);

        assertThat(s.purgeExpired())
                .as("An index entry whose record Redis already reclaimed is not a record this call deleted")
                .isZero();
        assertThat(commands.zcard("idempotency:idx")).isZero();
    }

    @Test
    void When_PurgeBacklogExceedsBatchSize_Expect_AllRecordsRemoved() throws InterruptedException {
        IdempotencyStore s =
                new RedisIdempotencyStore(connection, "idempotency:", 50, Duration.ofHours(1), 3, Clock.systemUTC());
        for (int i = 0; i < 10; i++) {
            s.tryAcquire(context("batched-key-" + i, Duration.ofMillis(10), Duration.ofMillis(2)));
        }

        Thread.sleep(50);

        assertThat(s.purgeExpired()).isEqualTo(10);
        assertThat(commands.zcard("idempotency:idx")).isZero();
    }

    @Test
    void When_ExtendLockCalled_Expect_IndexRescoredSoPurgeKeepsRecord() throws InterruptedException {
        IdempotencyStore s = store();
        // TTL shorter than the extended lock: without re-scoring the index, the record
        // would look purgeable while its lock is still live.
        s.tryAcquire(context("extend-rescore-key", Duration.ofMillis(10), Duration.ofMillis(10)));
        s.extendLock("extend-rescore-key", Duration.ofSeconds(30));

        Thread.sleep(50);

        assertThat(s.purgeExpired()).isZero();
        assertThat(commands.exists("idempotency:rec:extend-rescore-key")).isEqualTo(1);
    }

    @Test
    void When_HeadersContainMultipleValues_Expect_AllReplayed() {
        IdempotencyStore s = store();
        String key = "multi-header-key";
        Map<String, List<String>> headers = Map.of("Set-Cookie", List.of("a=1", "b=2"), "X-Trace", List.of("t-1"));

        s.tryAcquire(contextFor(key));
        s.complete(key, new StoredResponse(201, headers, "ok".getBytes(), Instant.now()), Duration.ofHours(1));

        AcquireResult result = s.tryAcquire(contextFor(key));

        assertThat(((AcquireResult.Duplicate) result).response().headers()).isEqualTo(headers);
    }

    @Test
    void When_CustomClockProvided_Expect_StoreConstructsSuccessfully() {
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);

        var store = new RedisIdempotencyStore(connection, "clock:", 50, Duration.ofHours(1), 100, fixedClock);

        assertThat(store).isNotNull();
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
    }

    @Test
    void When_NullConnection_Expect_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new RedisIdempotencyStore(null)).isInstanceOf(NullPointerException.class);
    }
}
