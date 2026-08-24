# idempotency4j

[![Maven Central](https://img.shields.io/maven-central/v/io.github.josipmusa/idempotency-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.josipmusa/idempotency-spring-boot-starter)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A Java idempotency library with pluggable storage backends and Spring Web / Spring Boot support.

Send the same request twice and, while the idempotency record is retained, get the same response
without normally running the handler again.

Idempotency storage closes the common client-retry window; it cannot by itself make an arbitrary
downstream side effect exactly-once. For payments and similarly critical work, combine it with a
database transaction, an outbox, or a downstream idempotency/fencing token.

## When to use this

Your API needs idempotency if clients can retry on network failure (payment processing, order creation, resource provisioning) and a duplicated request would cause a real problem — money charged twice, two orders shipped, two VMs started.

## Quick start

Add the Spring Boot starter and a storage backend:

Replace `VERSION` with the latest version shown in the Maven Central badge above.

```xml
<dependency>
    <groupId>io.github.josipmusa</groupId>
    <artifactId>idempotency-spring-boot-starter</artifactId>
    <version>VERSION</version>
</dependency>

<!-- Pick one storage backend -->
<dependency>
    <groupId>io.github.josipmusa</groupId>
    <artifactId>idempotency-jdbc</artifactId>
    <version>VERSION</version>
</dependency>

<!-- Or Redis -->
<dependency>
    <groupId>io.github.josipmusa</groupId>
    <artifactId>idempotency-redis</artifactId>
    <version>VERSION</version>
</dependency>
```

Or use the BOM to align all module versions:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.josipmusa</groupId>
            <artifactId>idempotency-bom</artifactId>
            <version>VERSION</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Annotate the endpoints that need idempotency:

```java
@PostMapping("/payments")
@Idempotent
public ResponseEntity<Payment> createPayment(@RequestBody PaymentRequest request) {
    // Subsequent identical requests normally get the stored response replayed.
    // The payment provider should also receive its own idempotency key.
    return ResponseEntity.ok(paymentService.charge(request));
}
```

Clients pass a client-generated key with each request:

```
POST /payments
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{ "amount": 100, "currency": "USD" }
```

If that key has been seen before with the same request body, the stored response is returned with `Idempotent-Replayed: true`. If the same key arrives with a different body, the request is rejected with `422 Unprocessable Entity`.

## The `@Idempotent` annotation

```java
@Idempotent(
    ttl = "PT24H",          // How long to keep the stored response (ISO-8601). Default: 24h
    lockTimeout = "PT10S",  // How long a concurrent duplicate waits. Default: 10s
    required = true         // Whether a missing key header is an error. Default: true
)
```

### Behavior when `required = false`

| Key header present | Behavior |
|--------------------|----------|
| Yes                | Full idempotency enforcement |
| No                 | Request passes through unmodified, no idempotency enforced |

Use `required = false` on endpoints where idempotency is optional — clients that care send a key, clients that do not are not rejected.

## Storage backends

| Module | Use when |
|--------|----------|
| `idempotency-jdbc` | You have a relational database. Supports MySQL and PostgreSQL. Schema is initialized automatically. |
| `idempotency-redis` | You have Redis. Standalone and Sentinel topologies; Redis Cluster is not supported. |
| `idempotency-inmemory` | Single-instance deployments, local development, and tests. Not suitable for horizontally-scaled environments. |

Declare the store as a bean — the starter wires the engine and filter around whichever `IdempotencyStore` it finds:

```java
@Bean
public IdempotencyStore idempotencyStore(DataSource dataSource) {
    return new JdbcIdempotencyStore(dataSource);
}
```

### Redis

`idempotency-redis` is built on [Lettuce](https://lettuce.io/) and needs a connection opened with the store's codec, so that binary response bodies are stored as raw bytes:

```java
@Bean(destroyMethod = "shutdown")
public RedisClient redisClient() {
    return RedisClient.create("redis://localhost:6379");
}

@Bean(destroyMethod = "close")
public StatefulRedisConnection<String, byte[]> idempotencyRedisConnection(RedisClient client) {
    return client.connect(RedisIdempotencyStore.CODEC);
}

@Bean
public IdempotencyStore idempotencyStore(StatefulRedisConnection<String, byte[]> connection) {
    return new RedisIdempotencyStore(connection);
}
```

Lettuce connections are thread-safe, so one connection can serve the application. The caller owns
that connection. Use the immutable configuration object for operational settings. Always choose an
application-specific prefix, even though the safe default is `idempotency4j:`:

```java
RedisIdempotencyStoreConfig config = RedisIdempotencyStoreConfig.builder()
        .keyPrefix("payments:idempotency:")
        .pollInterval(Duration.ofMillis(50))
        .retentionGrace(Duration.ofHours(1))
        .purgeBatchSize(500)
        .maxPurgePagesPerCall(100)
        .build();

return new RedisIdempotencyStore(connection, config);
```

Every record is marked with `owner=idempotency4j` and `formatVersion=1`. The store refuses to
overwrite a colliding string, an unowned hash, or an unsupported owned record. Purge skips all such
keys. Lock and expiry decisions use Redis server time, not the application clock.

Records carry their own expiry timestamps, and each write also sets a native Redis TTL that trails logical expiry by the retention grace (default 1h). Expired records are therefore reclaimed even if the purge job is disabled. `purgeExpired()` uses bounded, resumable SCAN pages to remove them promptly; there is no permanent global expiry index.

Use Redis 7 or newer. A dedicated Redis deployment is recommended. An existing cache configured
with an evicting policy is not suitable, even with a separate database number or prefix, because
memory limits and eviction policy apply to the Redis instance. Configure persistence appropriate to
your recovery objective and `maxmemory-policy noeviction`. Eviction is a correctness event, not
merely a cache miss: losing an IN_PROGRESS or COMPLETE record can allow the operation to execute
again. Size Redis for retained response bodies and use a `ResponseSanitizer` to remove sensitive or
unnecessarily large data.

Use TLS for remote connections and an ACL restricted to the configured prefix. The provider needs
`EVALSHA`, `EVAL`, `SCAN`, and optionally `WAIT`; its scripts use `TYPE`, `TIME`, `HGET`, `HSET`,
`HDEL`, `DEL`, `PEXPIRE`, and `PTTL`. Validate the exact ACL against your Redis version in staging.
For example, after replacing the username, password, and prefix:

```text
ACL SETUSER idempotency4j reset on >PASSWORD ~payments:idempotency:* resetchannels \
  +evalsha +eval +scan +wait +type +time +hget +hset +hdel +del +pexpire +pttl
```

Redis replication is asynchronous. Sentinel provides discovery and failover, but an acknowledged
write can still be absent from the promoted replica. Replica acknowledgement is disabled by
default. Enable bounded `WAIT` acknowledgement when that tradeoff is appropriate:

```java
new RedisIdempotencyStore(
        connection,
        RedisIdempotencyStoreConfig.builder()
                .keyPrefix("payments:idempotency:")
                .replicaAcknowledgement(
                        RedisReplicaAcknowledgement.require(1, Duration.ofMillis(100)))
                .build());
```

This reduces, but does not eliminate, failover data loss. It adds a synchronous round trip and can
cause head-of-line delay on the shared connection until the timeout when replicas are unavailable,
so keep the timeout bounded and load-test the failure path. If `WAIT` reports insufficient replicas,
the primary may already contain the mutation. The library throws `IdempotencyDurabilityException`
to distinguish that indeterminate outcome from a backend outage. For Sentinel topology discovery,
pass the `StatefulRedisMasterReplicaConnection` returned by Lettuce
`MasterReplica.connect(...)` and keep reads on the master.

There is intentionally no migration support for pre-release Redis record layouts. Foreign or
unsupported records fail closed rather than being guessed at, rewritten, or deleted.

## Configuration

All properties are prefixed with `idempotency`:

```yaml
idempotency:
  key-header: Idempotency-Key     # Header name carrying the key. Default: Idempotency-Key
  default-ttl: PT24H              # Default TTL for stored responses. Default: 24h
  default-lock-timeout: PT10S     # Default lock timeout. Default: 10s
  max-body-bytes: 1048576         # Max request body size to fingerprint in bytes. Default: 1 MiB
  filter-order: 0                 # Order of the idempotency filter in the filter chain. Default: 0
  purge:
    enabled: true                 # Whether to register the purge scheduler. Default: true
    cron: "0 0 * * * *"          # Cron expression for purging expired records. Default: hourly
```

Per-endpoint values in `@Idempotent` override these defaults.

## Framework support

idempotency4j currently supports **Spring MVC (Servlet-based)** applications only.

| Runtime | Status |
|---------|--------|
| Spring MVC (Servlet) | Supported |
| Spring WebFlux (Reactive) | Not supported |

The autoconfiguration activates only when a Servlet-based Spring Web application is detected (`@ConditionalOnWebApplication(type = SERVLET)`). In a WebFlux application it does nothing — no error is raised, the filter simply does not register.

## Known limitations

**No WebFlux/reactive support.** The filter is built on `OncePerRequestFilter` (Servlet API). A reactive `WebFilter`-based adapter is a candidate for a future release.

**Shared idempotency key namespace.** Keys are stored in a single global namespace within the backing store. There is no built-in per-tenant or per-user isolation. Two callers using the same key value share idempotency state. For multi-tenant environments, prefix keys with a tenant or user identifier at the application level (e.g. `userId:clientKey`).

**Infrastructure failures are not an exactly-once guarantee.** Lease fencing prevents an expired owner from overwriting a newer owner's stored result, but it cannot roll back a side effect that happened before a process crash or store failure. Use transactional/outbox patterns or propagate an idempotency/fencing token to downstream systems.

**Completion storage can be indeterminate.** A backend error may happen after the response record
was accepted, particularly while Redis is waiting for replica acknowledgement. The successful
business response is still sent. Use the typed store exceptions for logging and alerting; do not
automatically repeat the business operation based only on a completion-storage error.

**Redis Cluster is not supported.** The provider accepts Lettuce's non-cluster `StatefulRedisConnection`, and its bounded SCAN purge is not node-aware. Standalone and Sentinel master-replica connections are supported.

## Security considerations

The store persists full HTTP response bodies. Depending on your endpoints this may include PII, tokens, or financial data.

- Enable encryption at rest on the backing database.
- Use TLS and authentication/ACLs for Redis; restrict the ACL to the configured key prefix.
- Configure Redis with `maxmemory-policy noeviction` and monitor memory headroom.
- Use short TTL values to limit data retention.
- Configure `idempotency.purge.cron` to remove expired records promptly.
- Audit which endpoints are annotated `@Idempotent`, what their responses contain, and their maximum response size.

To strip or redact sensitive fields before storage, register a `ResponseSanitizer` bean. The default implementation is a no-op pass-through:

```java
@Bean
public ResponseSanitizer responseSanitizer() {
    return response -> {
        // Remove sensitive headers, redact body, etc.
        Map<String, List<String>> headers = new HashMap<>(response.headers());
        headers.remove("Set-Cookie");
        return new StoredResponse(response.statusCode(), headers, response.body(), response.completedAt());
    };
}
```

For vulnerability reporting, see [SECURITY.md](SECURITY.md).

## License

Apache 2.0. See [LICENSE](LICENSE).
