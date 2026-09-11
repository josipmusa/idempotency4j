<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/logo-dark.svg">
    <img src="docs/logo.svg" alt="idempotency4j" height="48">
  </picture>
</p>

# idempotency4j

**Idempotent HTTP endpoints for Spring Boot, with pluggable JDBC, Redis, and in-memory storage.**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.josipmusa/idempotency-spring-boot-starter?label=maven%20central)](https://central.sonatype.com/artifact/io.github.josipmusa/idempotency-spring-boot-starter)
[![CI](https://github.com/josipmusa/idempotency4j/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/josipmusa/idempotency4j/actions/workflows/ci.yml)
[![Javadoc](https://javadoc.io/badge2/io.github.josipmusa/idempotency-core/javadoc.svg)](https://javadoc.io/doc/io.github.josipmusa/idempotency-core)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Clients send an `Idempotency-Key` header. The first request runs your handler and its response is
stored; while that record is retained, a duplicate replays the stored response instead of running
the handler again. Storage is pluggable, and the core engine has no HTTP types in it, so non-HTTP
callers can drive it directly.

Your API needs this if clients retry on network failure (payment processing, order creation,
resource provisioning) and a duplicate would cause a real problem: money charged twice, two orders
shipped, two VMs started.

## What this is not

**This is not an exactly-once guarantee for arbitrary downstream side effects.** Lease fencing
protects the idempotency record, not the third-party charge your handler made just before the
process died. If you need that guarantee, you still need a shared transaction, a transactional
outbox, or an idempotency key passed to the downstream service. This library makes *your endpoint*
safe to retry; it cannot make *someone else's* endpoint safe to retry for you.

Two more things it deliberately is not: a distributed lock you can borrow for general use, and a
WebFlux library. The bundled adapter is Servlet-only.

## Contents

- [Requirements](#requirements)
- [Quick start](#quick-start)
- [How it works](#how-it-works)
- [HTTP semantics](#http-semantics)
- [The `@Idempotent` annotation](#the-idempotent-annotation)
- [Storage backends](#storage-backends)
- [Configuration](#configuration)
- [Using the engine outside HTTP](#using-the-engine-outside-http)
- [Lifecycle callbacks](#lifecycle-callbacks)
- [Limitations](#limitations)
- [Security](#security)

## Requirements

| | Supported | Notes |
|---|---|---|
| Java | 21+ | Built and tested on 21 |
| Spring Boot | 3.5.x | Built against 3.5.16 |
| Spring MVC (Servlet) | Yes | The autoconfiguration activates only for Servlet web applications |
| Spring WebFlux | No | Nothing registers, and no error is raised |
| PostgreSQL | Tested on 16 | Via `idempotency-jdbc` |
| MySQL | Tested on 8.0 | Via `idempotency-jdbc` |
| Redis | 7+, tested on 7 | Standalone and Sentinel. Redis Cluster is not supported |

## Quick start

Add the Spring Boot starter and one storage backend:

```xml
<dependency>
    <groupId>io.github.josipmusa</groupId>
    <artifactId>idempotency-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>

<!-- Pick one storage backend -->
<dependency>
    <groupId>io.github.josipmusa</groupId>
    <artifactId>idempotency-jdbc</artifactId>
    <version>0.2.0</version>
</dependency>
```

Or import the BOM and omit the versions:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.josipmusa</groupId>
            <artifactId>idempotency-bom</artifactId>
            <version>0.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Declare the store and annotate the endpoints that need idempotency:

```java
@Bean
public IdempotencyStore idempotencyStore(DataSource dataSource) {
    return new JdbcIdempotencyStore(dataSource);
}
```

```java
@PostMapping("/payments")
@Idempotent
public ResponseEntity<Payment> createPayment(@RequestBody PaymentRequest request) {
    // Duplicates get the stored response replayed.
    // The payment provider should also receive its own idempotency key.
    return ResponseEntity.ok(paymentService.charge(request));
}
```

Clients pass a key they generate themselves:

```http
POST /payments
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{ "amount": 100, "currency": "USD" }
```

Send it twice and the second response comes back from the store, carrying
`Idempotent-Replayed: true`. Send the same key with a different body and it is rejected with `422`.

## How it works

Every request carrying a key resolves down one of four paths, decided entirely by the state the
record already holds in the store. A record is identified by a *scope* and the key together: the
filter uses the handler method as the scope (`PaymentController.create`), so the same key sent to
two endpoints is two independent records.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/request-outcomes-dark.png">
  <img alt="Flowchart. A request with an Idempotency-Key is routed by the key's state in the store: a new key runs the handler and stores its response, a completed key replays the stored response when the request body matches and is rejected with 422 when it does not, and a key still in progress past the wait timeout is refused with 503." src="docs/diagrams/request-outcomes.png">
</picture>

The blocking happens inside the store, not in the engine. A concurrent duplicate waits inside
`tryAcquire` for the holder to finish and only surfaces as `503` once its `wait` elapses, which
is why a duplicate arriving mid-flight usually gets the real response rather than an error. A
caller that must not block - a message listener on a consumer thread - sets `wait` to zero and is
told the record is in flight straight away.

Behind that, each record moves through a small state machine. Every acquisition carries a lease,
and `complete`, `release`, and the heartbeat all have to present a matching lease, which is what
fences a stale owner out after its lease has been stolen:

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/record-lifecycle-dark.png">
  <img alt="State machine. A record is created in progress when a caller acquires a lease, which a heartbeat keeps extending while the action runs and which the next caller may steal once it expires. The record then either completes with a replayable payload or fails and becomes reclaimable, and both terminal states are purged when their time to live elapses." src="docs/diagrams/record-lifecycle.png">
</picture>

Two durations govern this, and they are set independently. `lease` is how long an acquisition is
protected; `wait` is how long a second caller blocks for someone else's. The heartbeat fires at
`lease / 2`, so a handler that legitimately runs longer than its lease keeps it rather than having
it stolen mid-flight. A handler that dies without releasing leaves an expired lease, which the next
`tryAcquire` steals atomically.

Responsibilities are split across three layers, and the boundaries are enforced by design:

| Layer | Module | Owns |
|---|---|---|
| Engine | `idempotency-core` | Orchestration, heartbeat, release on failure. No HTTP types. Never calls `complete` |
| Adapter | `spring/idempotency-spring-web` | Servlet capture and replay, error mapping, calling `complete` with the engine's lease |
| Store | `providers/*` | The SPI. All blocking, waiting, and stale-lock stealing happens inside `tryAcquire` |

## HTTP semantics

### What gets stored

The filter stores whatever your handler returns, **including 4xx and 5xx responses**, as long as
the handler returns normally. A handler that returns `500` has that `500` replayed to every
duplicate for the full TTL.

A handler that *throws* is different: the engine releases the lock, the record is marked `FAILED`,
and the next request with that key reclaims it and runs the handler again.

If you want a failed request to be retriable, throw. If you return an error status, you are
telling the library that error is the final answer for that key.

### Status codes the filter can return

These come from the filter itself, before or instead of your handler. Each carries a
`{"error": "..."}` JSON body.

| Status | When |
|---|---|
| `413 Payload Too Large` | Request body exceeds `idempotency.max-body-bytes` |
| `422 Unprocessable Entity` | Key header missing or blank while `required = true` |
| `422 Unprocessable Entity` | Key longer than 255 characters |
| `422 Unprocessable Entity` | Key reused with a different request body |
| `503 Service Unavailable` | Another request still holds the key after `waitTimeout` |

### Response headers on a replay

| Header | Value |
|---|---|
| `Idempotent-Replayed` | `true` |
| `Cache-Control` | `no-store` |

The stored status code and headers are replayed as they were captured. A key completed by a
non-HTTP caller has no response to replay, so an HTTP duplicate for that key gets `204 No Content`.

## The `@Idempotent` annotation

```java
@Idempotent(
    ttl = "PT24H",          // How long to keep the stored response (ISO-8601). Default: 24h
    lease = "PT30S",        // How long this request's acquisition is protected. Default: 30s
    waitTimeout = "PT10S",  // How long a concurrent duplicate blocks. "PT0S" to not block. Default: 10s
    required = true         // Whether a missing key header is an error. Default: true
)
```

With `required = false`, a request that carries a key gets full idempotency enforcement and one
that does not passes straight through. Use it on endpoints where idempotency is opt-in: clients
that care send a key, clients that do not are not rejected.

Per-endpoint values override the global defaults in [Configuration](#configuration).

## Storage backends

| Module | Use when |
|---|---|
| `idempotency-jdbc` | You have a relational database. PostgreSQL and MySQL. Schema initialized automatically |
| `idempotency-redis` | You have Redis. Standalone and Sentinel topologies |
| `idempotency-inmemory` | Single-instance deployments, local development, and tests. Not for horizontally-scaled environments |

The starter wires the engine and the HTTP filter around whichever `IdempotencyStore` bean you
provide.

<details>
<summary><b>JDBC configuration</b></summary>

Provide a `DataSource`. By default the store creates and manages its own schema:

```java
@Bean
public IdempotencyStore idempotencyStore(DataSource dataSource) {
    return new JdbcIdempotencyStore(dataSource);
}
```

To manage the schema with Flyway, Liquibase, or another tool, initialize it from the bundled
`idempotency-schema-postgresql.sql` or `idempotency-schema-mysql.sql` and turn off automatic
initialization:

```java
@Bean
public IdempotencyStore idempotencyStore(DataSource dataSource) {
    return new JdbcIdempotencyStore(dataSource, false);
}
```

</details>

<details>
<summary><b>Redis configuration</b></summary>

The Redis store uses [Lettuce](https://lettuce.io/). Open the connection with
`RedisIdempotencyStore.CODEC` so response bodies stay binary-safe. The application owns the client
and the connection, which is why the beans declare their shutdown methods:

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

Use Redis 7 or newer. Choose an application-specific key prefix, and configure the server with
`maxmemory-policy noeviction` so records are not evicted out from under the store. One thread-safe
connection can serve the store.

</details>

### Adding a backend

`IdempotencyStoreContract` in `idempotency-test` is the single source of truth for store behavior.
Implement the SPI, extend the contract, implement `store()`, and pass all of it. Behavior changes
belong in the contract first, so every backend is held to them.

## Configuration

All properties are prefixed with `idempotency`:

```yaml
idempotency:
  key-header: Idempotency-Key     # Header carrying the key. Default: Idempotency-Key
  default-ttl: PT24H              # Default TTL for stored responses. Default: 24h
  default-lease: PT30S            # Default lease on an acquisition. Default: 30s
  default-wait: PT10S             # Default wait for an in-flight key. Default: 10s
  max-body-bytes: 1048576         # Max request body size to fingerprint, in bytes. Default: 1 MiB
  filter-order: 0                 # Order of the filter in the chain. Default: 0
  purge:
    enabled: true                 # Register the purge scheduler. Default: true
    cron: "0 0 * * * *"           # Cron for purging expired records. Default: hourly
```

## Using the engine outside HTTP

`idempotency-core` has no HTTP types in it. Drive the engine directly from a message listener, an
event handler, or anything else that needs a key to run at most once. The annotation, the filter,
and `StoredResponse` are the Spring adapter's business, not the engine's.

A caller with no request body to hash builds a context without a fingerprint, and completes with
`Payload.none()` because there is nothing for a duplicate to replay. The first argument is the
scope: name the unit of work, so two listeners handling the same event id each get their own
record.

```java
IdempotencyEngine engine = new IdempotencyEngine(store, scheduler);

IdempotencyContext context = IdempotencyContext.builder("ShipmentListener.onOrderShipped", event.id())
        .ttl(Duration.ofHours(24))
        .leaseDuration(Duration.ofSeconds(30))
        .waitTimeout(Duration.ZERO)   // decline instead of parking the consumer thread
        .build();

ExecutionResult result = engine.execute(context, () -> handler.handle(event));

switch (result) {
    case ExecutionResult.Executed executed ->
            engine.complete(context, executed.leaseId(), Payload.none(), context.ttl());
    case ExecutionResult.Duplicate ignored -> {
        // already handled under this key, nothing to do
    }
}
```

As in the HTTP flow, the engine acquires the lock and runs the action with a heartbeat, but calling
`complete` is the caller's job: only the caller knows what, if anything, is worth storing for a
duplicate. Add `.fingerprint(sha256Hex)` to the builder when the payload is worth guarding against
key reuse.

When a duplicate should get a real result back, store a `Payload` instead of `Payload.none()`: a
`type` saying how to read the bytes, the bytes themselves, and flat string `attributes` the store
returns verbatim. That last part is what a messaging adapter uses to carry correlation data - the
ids of the messages the first execution published, say - so a duplicate can reference them instead
of publishing again.

```java
Payload payload = new Payload(
        "shipment/handled",
        objectMapper.writeValueAsBytes(outcome),
        Map.of("publicationId", publication.id()));

engine.complete(context, executed.leaseId(), payload, context.ttl());
```

Wrap the encoding in a `PayloadCodec<T>` when more than one call site stores the same shape; that
is what `StoredResponseCodec` is for HTTP responses.

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
        public void onCompleted(IdempotencyContext ctx, String leaseId, Payload payload) {
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
- An in-flight result or a fingerprint mismatch acquires no lease and fires nothing. Heartbeat activity
  is not surfaced either.

Outside Spring, pass the listeners to the engine directly:

```java
IdempotencyEngine engine = new IdempotencyEngine(store, scheduler, List.of(auditListener));
```

## Limitations

**No WebFlux or reactive support.** The filter is built on `OncePerRequestFilter` (Servlet API). A
reactive `WebFilter` adapter is a candidate for a future release.

**No messaging adapter.** Non-HTTP callers drive `IdempotencyEngine` directly; there is no
ready-made listener integration yet.

**No tenant isolation.** Records are scoped per handler method, but within a scope there is no
built-in per-tenant or per-user isolation: two callers sending the same key to the same endpoint
share idempotency state. In multi-tenant environments, prefix keys at the application level (for
example `userId:clientKey`).

**Redis Cluster is not supported.** The provider takes Lettuce's non-cluster
`StatefulRedisConnection`, and its bounded SCAN purge is not node-aware. Standalone and Sentinel
master-replica connections work.

**Downstream side effects.** See [What this is not](#what-this-is-not).

## Security

The store persists full HTTP response bodies. Depending on your endpoints, that may include PII,
tokens, or financial data.

- Enable encryption at rest on the backing database.
- Use TLS and ACLs for Redis, and restrict the ACL to the configured key prefix.
- Keep TTL values short to limit retention, and let `idempotency.purge.cron` remove expired records
  promptly.
- Audit which endpoints are annotated `@Idempotent`, what their responses contain, and how large
  those responses can get.

To strip or redact sensitive fields before storage, register a `ResponseSanitizer` bean
(`io.github.josipmusa.idempotency.spring.web.ResponseSanitizer`). The default is a no-op
pass-through:

```java
@Bean
public ResponseSanitizer responseSanitizer() {
    return response -> {
        Map<String, List<String>> headers = new HashMap<>(response.headers());
        headers.remove("Set-Cookie");
        return new StoredResponse(response.statusCode(), headers, response.body());
    };
}
```

To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Project

- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)
- [Code of conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)
- [API documentation](https://javadoc.io/doc/io.github.josipmusa/idempotency-core)

## License

Apache 2.0. See [LICENSE](LICENSE).
