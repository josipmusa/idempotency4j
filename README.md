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

## Using the engine outside HTTP

`idempotency-core` has no HTTP types in it. Drive the engine directly from a message listener, an
event handler, or anything else that needs a key to run at most once - the annotation, the filter
and `StoredResponse` are the Spring adapter's business, not the engine's.

A caller with no request body to hash builds a context without a fingerprint, and completes with
`NoPayload` because there is nothing for a duplicate to replay:

```java
IdempotencyEngine engine = new IdempotencyEngine(store, scheduler);

IdempotencyContext context = IdempotencyContext.withoutFingerprint(
        "order-shipped:" + event.id(), Duration.ofHours(24), Duration.ofSeconds(10));

ExecutionResult result = engine.execute(context, () -> handler.handle(event));

switch (result) {
    case ExecutionResult.Executed executed ->
            engine.complete(context, executed.leaseId(), NoPayload.at(Instant.now()), context.ttl());
    case ExecutionResult.Duplicate ignored -> {
        // already handled under this key - nothing to do
    }
}
```

As in the HTTP flow, the engine acquires the lock and runs the action with a heartbeat, but calling
`complete` is the caller's job: only the caller knows what, if anything, is worth storing for a
duplicate. Pass a fingerprint (`new IdempotencyContext(key, ttl, lockTimeout, sha256Hex)`) when the
payload is worth guarding against key reuse, and a `StoredResponse` to `complete` when a duplicate
should get a real result back.

Complete through `engine.complete(...)` rather than `store.complete(...)`: it does the same store
call but also fires the [lifecycle callbacks](#lifecycle-callbacks).

## Lifecycle callbacks

Register an `IdempotencyLifecycleListener` bean to observe the idempotent boundary. The starter
picks up every listener bean and honours `@Order`; no other configuration is needed.

```java
@Bean
public IdempotencyLifecycleListener auditListener(AuditService audit) {
    return new IdempotencyLifecycleListener() {
        @Override
        public void onAcquired(IdempotencyContext ctx, String leaseId) {
            audit.begin(ctx.key());   // runs on the request thread, before the handler
        }

        @Override
        public void onCompleted(IdempotencyContext ctx, String leaseId, IdempotencyPayload payload) {
            audit.end(ctx.key());
        }

        @Override
        public void onFailed(IdempotencyContext ctx, String leaseId, Throwable cause, FailurePhase phase) {
            audit.abandon(ctx.key(), phase);
        }
    };
}
```

The contract, in short:

- Callbacks run **synchronously on the calling thread** (the servlet thread, for HTTP), in
  registration order. That is deliberate: it lets a listener bind thread-local state that the
  guarded action then sees. A listener that blocks blocks the request.
- Exceptions thrown by a listener are logged at WARN and swallowed. They never change the stored
  payload, the response, or the exception the engine is propagating.
- Every acquired lease gets **exactly one** terminal callback: `onCompleted` **or** `onFailed`,
  always preceded by `onAcquired` with the same lease. Use the pair to unbind whatever
  `onAcquired` bound.
- `onDuplicate` stands alone - a duplicate acquires no lease, so no terminal callback follows.
- `onCompleted` fires only once the store has confirmed the completion. An unconfirmed durability
  guarantee counts as `onFailed` with `FailurePhase.COMPLETION`, which means the action's side
  effects happened but a retry will most likely run them again.
- A lock timeout or a fingerprint mismatch acquires no lease and fires nothing. Heartbeat activity
  is not surfaced either.

Outside Spring, pass the listeners to the engine directly:

```java
IdempotencyEngine engine = new IdempotencyEngine(store, scheduler, List.of(auditListener));
```

## Framework support

The bundled *adapter* supports **Spring MVC (Servlet-based)** applications only. The engine itself
is transport-neutral - see [Using the engine outside HTTP](#using-the-engine-outside-http).

| Runtime | Status |
|---------|--------|
| Spring MVC (Servlet) | Supported |
| Spring WebFlux (Reactive) | Not supported |

The autoconfiguration activates only when a Servlet-based Spring Web application is detected (`@ConditionalOnWebApplication(type = SERVLET)`). In a WebFlux application it does nothing: no error is raised, and the filter does not register.

## Known limitations

**No WebFlux/reactive support.** The filter is built on `OncePerRequestFilter` (Servlet API). A reactive `WebFilter`-based adapter is a candidate for a future release.

**No messaging adapter.** Non-HTTP callers drive `IdempotencyEngine` directly; there is no ready-made message-listener integration yet.

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

To strip or redact sensitive fields before storage, register a `ResponseSanitizer` bean (`io.github.josipmusa.idempotency.spring.web.ResponseSanitizer`). The default implementation is a no-op pass-through:

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
