# idempotency4j

A Java library that adds idempotency to Spring Boot APIs. Send the same request twice — get the same response, side effects run exactly once.

Built around a clean separation between the orchestration engine, the persistence store, and the framework adapter. The engine knows nothing about HTTP or databases; the store knows nothing about Spring; the adapter knows nothing about lock lifecycles.

## When to use this

Your API needs idempotency if clients can retry on network failure (payment processing, order creation, resource provisioning) and a duplicated request would cause a real problem — money charged twice, two orders shipped, two VMs started.

The alternative is building idempotency ad-hoc per endpoint. This library gives you that as infrastructure.

## Quick start

Add the Spring Boot starter and a storage backend to your project:

```xml
<dependency>
    <groupId>io.github.josipmusa</groupId>
    <artifactId>idempotency-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>

<!-- Pick one storage backend -->
<dependency>
    <groupId>io.github.josipmusa</groupId>
    <artifactId>idempotency-jdbc</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Or use the BOM to align all module versions:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.josipmusa</groupId>
            <artifactId>idempotency-bom</artifactId>
            <version>0.0.1-SNAPSHOT</version>
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
    // Runs exactly once per unique Idempotency-Key header value.
    // Subsequent identical requests get the stored response replayed.
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

If that key has been used before with the same request body, the stored response is returned with `Idempotent-Replayed: true`. If the same key arrives with a different body, the request is rejected with `422 Unprocessable Entity`.

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
| Yes                | Full idempotency enforcement — same as `required = true` |
| No                 | Request passes through unmodified, no idempotency enforced |

Use `required = false` on endpoints where idempotency is optional — clients that care send a key, clients that do not are not rejected.

## Storage backends

| Module | Use when |
|--------|----------|
| `idempotency-jdbc` | You have a relational database. Tested with MySQL and PostgreSQL. |
| `idempotency-inmemory` | Single-instance deployments, local development, tests. Not suitable for horizontally-scaled environments. |

For JDBC, the store needs a table. Run this migration before starting the application:

```sql
CREATE TABLE idempotency_records (
    idempotency_key   VARCHAR(255)  NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    request_fingerprint VARCHAR(64),
    response_status   INT,
    response_headers  TEXT,
    response_body     MEDIUMBLOB,
    created_at        DATETIME(3)   NOT NULL,
    lock_expires_at   DATETIME(3),
    expires_at        DATETIME(3),
    PRIMARY KEY (idempotency_key)
);
```

## Configuration

All properties are prefixed with `idempotency`:

```yaml
idempotency:
  key-header: Idempotency-Key     # Header name carrying the key. Default: Idempotency-Key
  default-ttl: PT24H              # Default TTL for stored responses. Default: 24h
  default-lock-timeout: PT10S     # Default lock timeout. Default: 10s
  key-required: true              # Global default for @Idempotent(required). Default: true
  max-body-bytes: -1              # Max request body size to fingerprint (-1 = unlimited). Default: -1
  purge:
    cron: "0 0 * * * *"          # Cron expression for purging expired records. Default: hourly
```

Per-endpoint values in `@Idempotent` override these defaults.

## How it works

Three layers, each with one responsibility:

**Engine** (`IdempotencyEngine`) — acquires the lock, runs the action, manages the heartbeat. Does not know about HTTP or databases.

**Store** (`IdempotencyStore`) — handles persistence and in-flight blocking. `tryAcquire` is synchronous and fully resolved: the engine never polls. Implemented by the jdbc/inmemory modules.

**Adapter** (`IdempotencyFilter`) — translates the HTTP request into an `IdempotencyContext`, calls the engine, stores the response on completion, and replays it on duplicates.

### Key lifecycle

```
[not exists] ──tryAcquire──> IN_PROGRESS ──complete()──> COMPLETE
                                  │
                              release()   (action failed)
                                  │
                                  v
                               FAILED ──tryAcquire──> IN_PROGRESS
```

`COMPLETE` keys return the stored response on all subsequent requests until TTL expires. `IN_PROGRESS` keys whose lock has expired are stealable by the next caller (handles crashes).

### Request fingerprinting

Every request body is hashed (SHA-256) and stored alongside the key. A second request with the same key but a different body hash is rejected with `422` — it is a different operation, not a retry.

### Expiry and purging

The starter registers a scheduled task that calls `purgeExpired()` on the store using the cron expression from `idempotency.purge.cron`. The task only runs if `@EnableScheduling` is present. If you are not using the starter, call `purgeExpired()` yourself from a `ScheduledExecutorService`.

## Implementing a custom store

Implement `IdempotencyStore` from `idempotency-core`:

```java
public interface IdempotencyStore {
    AcquireResult tryAcquire(IdempotencyContext context);
    void complete(String key, StoredResponse response, Duration ttl);
    void release(String key);
    void extendLock(String key, Duration extension);
    int purgeExpired();
}
```

Extend `IdempotencyStoreContract` from `idempotency-test` — that contract test suite verifies all behavioral requirements including lock stealing, fingerprint mismatches, and concurrent access.

```java
class MyStoreTest extends IdempotencyStoreContract {
    @Override
    protected IdempotencyStore store() {
        return new MyIdempotencyStore(...);
    }
}
```

## Security considerations

The store persists full HTTP response bodies. Depending on your endpoints this may include PII, tokens, or financial data.

- Enable encryption at rest on the backing database.
- Use short TTL values to limit retention.
- Configure `idempotency.purge.cron` to remove expired records promptly.
- Audit which endpoints are annotated `@Idempotent` and what their responses contain.

For vulnerability reporting, see [SECURITY.md](SECURITY.md).

## License

Apache 2.0. See [LICENSE](LICENSE).
