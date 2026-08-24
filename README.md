# idempotency4j

[![Maven Central](https://img.shields.io/maven-central/v/io.github.josipmusa/idempotency-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.josipmusa/idempotency-spring-boot-starter)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A Java idempotency library with pluggable storage backends and Spring Web / Spring Boot support.

Send the same request twice and, while its idempotency record is retained, completed requests replay
the stored response instead of running the handler again.

## When to use this

Your API needs idempotency if clients can retry on network failure (payment processing, order creation, resource provisioning) and a duplicated request would cause a real problem: money charged twice, two orders shipped, or two VMs started.

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

Use `required = false` on endpoints where idempotency is optional. Clients that care send a key;
clients that do not are not rejected.

## Storage backends

| Module | Use when |
|--------|----------|
| `idempotency-jdbc` | You have a relational database. Supports MySQL and PostgreSQL. Schema is initialized automatically. |
| `idempotency-redis` | You have Redis. Standalone and Sentinel topologies; Redis Cluster is not supported. |
| `idempotency-inmemory` | Single-instance deployments, local development, and tests. Not suitable for horizontally-scaled environments. |

The Spring Boot starter wires the engine and HTTP filter around the `IdempotencyStore` bean you
provide.

### JDBC

Provide a `DataSource` and construct the JDBC store. By default, the store creates and manages its
schema:

```java
@Bean
public IdempotencyStore idempotencyStore(DataSource dataSource) {
    return new JdbcIdempotencyStore(dataSource);
}
```

To manage the schema with Flyway, Liquibase, or another tool, initialize it from the bundled MySQL
or PostgreSQL schema and disable automatic initialization:

```java
@Bean
public IdempotencyStore idempotencyStore(DataSource dataSource) {
    return new JdbcIdempotencyStore(dataSource, false);
}
```

### Redis

The Redis store uses [Lettuce](https://lettuce.io/). Open its connection with
`RedisIdempotencyStore.CODEC` so response bodies remain binary-safe. The application owns the
client and connection, which is why the beans declare their shutdown methods:

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
    RedisIdempotencyStoreConfig config = RedisIdempotencyStoreConfig.builder()
            .keyPrefix("payments:idempotency:")
            .build();

    return new RedisIdempotencyStore(connection, config);
}
```

Use Redis 7 or newer. Choose an application-specific key prefix and configure Redis persistence
with `maxmemory-policy noeviction`. One thread-safe connection can serve the store. Standalone and
Sentinel deployments are supported; Redis Cluster is not.

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

The autoconfiguration activates only when a Servlet-based Spring Web application is detected (`@ConditionalOnWebApplication(type = SERVLET)`). In a WebFlux application it does nothing: no error is raised, and the filter does not register.

## Known limitations

**No WebFlux/reactive support.** The filter is built on `OncePerRequestFilter` (Servlet API). A reactive `WebFilter`-based adapter is a candidate for a future release.

**Shared idempotency key namespace.** Keys are stored in a single global namespace within the backing store. There is no built-in per-tenant or per-user isolation. Two callers using the same key value share idempotency state. For multi-tenant environments, prefix keys with a tenant or user identifier at the application level (e.g. `userId:clientKey`).

**Arbitrary downstream effects are not an exactly-once guarantee.** Lease fencing protects the
idempotency record, but it cannot roll back an external side effect completed before a process
failure. Use a shared transaction, a transactional outbox, or a downstream idempotency key when
that guarantee is required.

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
