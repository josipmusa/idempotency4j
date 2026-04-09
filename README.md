# idempotency4j

A Java idempotency library with pluggable storage backends and Spring Web / Spring Boot support.

Send the same request twice — get the same response, side effects run exactly once.

## When to use this

Your API needs idempotency if clients can retry on network failure (payment processing, order creation, resource provisioning) and a duplicated request would cause a real problem — money charged twice, two orders shipped, two VMs started.

## Quick start

Add the Spring Boot starter and a storage backend:

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
    // Runs exactly once per unique Idempotency-Key value.
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
| `idempotency-inmemory` | Single-instance deployments, local development, and tests. Not suitable for horizontally-scaled environments. |

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

## Security considerations

The store persists full HTTP response bodies. Depending on your endpoints this may include PII, tokens, or financial data.

- Enable encryption at rest on the backing database.
- Use short TTL values to limit data retention.
- Configure `idempotency.purge.cron` to remove expired records promptly.
- Audit which endpoints are annotated `@Idempotent` and what their responses contain.

To strip or redact sensitive fields before storage, register a `ResponseSanitizer` bean. The default implementation is a no-op pass-through:

```java
@Bean
public ResponseSanitizer responseSanitizer() {
    return response -> {
        // Remove sensitive headers, redact body, etc.
        Map<String, List<String>> headers = new HashMap<>(response.headers());
        headers.remove("Set-Cookie");
        return new StoredResponse(response.statusCode(), headers, response.body(), response.storedAt());
    };
}
```

For vulnerability reporting, see [SECURITY.md](SECURITY.md).

## License

Apache 2.0. See [LICENSE](LICENSE).
