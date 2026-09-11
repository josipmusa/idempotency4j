# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Breaking.** `lockTimeout` is split into two independent durations. `leaseDuration` is how long
  an acquisition is protected before another caller may steal it (default 30s, heartbeat at half
  of it); `waitTimeout` is how long `tryAcquire` blocks for someone else's in-flight record before
  giving up (default 10s). A zero wait is valid and means "do not block", which is what lets a
  message listener decline instead of parking a consumer thread - impossible when one value meant
  both. `IdempotencyConfig.defaultLockTimeout` becomes `defaultLeaseDuration` and
  `defaultWaitTimeout`; the starter properties `idempotency.default-lock-timeout` become
  `idempotency.default-lease` and `idempotency.default-wait`; the `@Idempotent` attribute
  `lockTimeout` becomes `lease` and `waitTimeout` (the latter cannot be called `wait` - an
  annotation element by that name clashes with `Object.wait()`).
- **Breaking.** `IdempotencyContext` is no longer a record. It is built through
  `IdempotencyContext.builder(scope, key)` or `builder(identity)`, with `ttl`, `leaseDuration`,
  `waitTimeout` and `fingerprint` defaulted; the public constructors and
  `IdempotencyContext.withoutFingerprint(...)` are gone. Accessors are unchanged apart from
  `lockTimeout()`, which is replaced by `leaseDuration()` and `waitTimeout()`.
- **Breaking.** `AcquireResult.LockTimeout(identity)` becomes `AcquireResult.InFlight(retryAfter)`.
  `retryAfter` is the holder's remaining lease at the moment the store gave up, floored at zero, so
  a caller can say how long to wait before retrying. The engine still maps it to
  `IdempotencyLockTimeoutException`, which a later change replaces with an outcome.
- **Breaking.** The JDBC schema renames `lock_expires_at` to `lease_expires_at` and drops
  `locked_at` and `lock_timeout_ms`. The Redis hash renames `lockExpiresAt` to `leaseExpiresAt` and
  drops `lockTimeoutMs`. There is no migration; recreate the table and discard existing records.
- **Breaking.** `release` no longer rewrites `expires_at`. A FAILED record keeps the TTL it was
  created with instead of being re-dated from the lease, so a purge cannot drop it before the retry
  arrives. FAILED records therefore live for the full TTL rather than one lease.

- **Breaking.** A record is identified by a scope and a key together, never by the key alone. The
  new `IdempotencyIdentity(scope, key)` record in core is what every store dedupes on;
  `IdempotencyContext` holds an `IdempotencyIdentity` (`scope()` and `key()` delegate to it) and
  is built through `IdempotencyContext.builder(scope, key)`.
  `IdempotencyStore.complete`, `release` and `extendLock` take an `IdempotencyIdentity` where they
  took a `String key`; `IdempotencyLockTimeoutException` and
  `IdempotencyFingerprintMismatchException` carry the identity (`getIdentity()` replaces
  `getKey()`). `IdempotencyContext.MAX_KEY_LENGTH` moved to `IdempotencyIdentity`, which also
  defines `MAX_SCOPE_LENGTH` (128). The same message id delivered to two consumers, or the same
  `Idempotency-Key` sent to two endpoints, is now two units of work with two records instead of one
  silently skipping the other's work.
- **Breaking.** `IdempotencyFilter` scopes every record by its handler method, formatted as
  `<simple class name>.<method name>` (for example `PaymentController.create`).
  `IdempotentHandlerRegistry` resolves the scope at startup and fails fast if it exceeds 128
  characters.
- **Breaking.** The JDBC schema gains a `scope VARCHAR(128) NOT NULL` column and its primary key
  becomes `(scope, idempotency_key)`. Every statement filters on both columns. There is no migration
  from the key-only table; recreate it.
- **Breaking.** The Redis record key becomes `<prefix>rec:<scope>:<key>`. Records written under the
  previous `<prefix>rec:<key>` layout are not read.
- **Breaking.** `idempotency-core` no longer models HTTP. The engine, the store SPI and the
  context are transport-neutral, so a message listener or an event handler can drive them the
  same way the Servlet filter does.
- **Breaking.** `IdempotencyContext.requestFingerprint` is now optional. Leave `fingerprint(..)`
  off the builder to build a context without one; `fingerprint()` returns it as an `Optional`. A blank string is still rejected - `null` is how a caller says "none".
  Two acquisitions mismatch only when both carry a fingerprint and the two differ; a fingerprint
  present on just one side proceeds normally, because a caller that does not fingerprint its
  payload cannot contradict one that does.
- **Breaking.** `IdempotencyPayload` replaces `StoredResponse` in `AcquireResult.Duplicate`,
  `ExecutionResult.Duplicate` and `IdempotencyStore.complete(...)`, and the accessor on both
  `Duplicate` records is now `payload()` rather than `response()`. `StoredResponse` is unchanged
  apart from implementing the new interface, and is joined by `NoPayload` for callers with
  nothing to replay. Stores round-trip both variants; no schema or Redis format change was
  needed, because a completed record with no response code already reads back unambiguously.
- **Breaking.** `ResponseSanitizer` moved from `io.github.josipmusa.idempotency.core` to
  `io.github.josipmusa.idempotency.spring.web`. It only ever sanitized HTTP responses.
- **Breaking.** `IdempotencyConfig.keyHeader` moved to the new
  `io.github.josipmusa.idempotency.spring.web.WebIdempotencyConfig`; core config keeps
  the duration defaults. The `idempotency.key-header` property is unchanged, and
  the starter registers a `WebIdempotencyConfig` bean from it. Applications constructing
  `IdempotencyFilter` by hand pass a `WebIdempotencyConfig` where they passed `IdempotencyConfig`.
- **Breaking.** `IdempotencyFilter` records completion through `IdempotencyEngine.complete(...)`
  instead of calling the store itself, so HTTP requests fire the lifecycle callbacks. It no longer
  takes an `IdempotencyStore` parameter, because it no longer touches the store; applications
  constructing the filter by hand drop that argument.
- `idempotency-core` now depends on `slf4j-api` so the engine can report a misbehaving lifecycle
  listener. It remains free of framework dependencies.

### Added

- `NoPayload` for recording a completed operation that has nothing to replay, so non-HTTP callers
  can use the engine without fabricating a response.
- `IdempotencyLifecycleListener` observes the idempotent boundary: `onAcquired`, `onCompleted`,
  `onFailed` and `onDuplicate`. Callbacks run synchronously on the calling thread in registration
  order, so a listener can bind thread-local state that the guarded action then sees. Every
  acquired lease gets exactly one terminal callback - `onCompleted` or `onFailed` - always
  preceded by `onAcquired`, which is what lets a listener unbind that state reliably. Listener
  exceptions are logged and swallowed. The Spring Boot starter wires every listener bean into the
  engine and honours `@Order`.
- `IdempotencyEngine.complete(context, leaseId, payload, ttl)` wraps `IdempotencyStore.complete`
  and fires the completion callbacks around it, rethrowing store failures unchanged. Callers that
  drive the engine directly should complete through it rather than through the store, so the
  callbacks fire.

## [0.2.0] - 2026-08-31

### Added
- Redis provider (`idempotency-redis`) built on Lettuce, with Lua-scripted state transitions,
  bounded resumable SCAN-based purging, and a native Redis TTL as the memory-reclamation
  backstop. Supports standalone and Sentinel topologies, plus optional Redis `WAIT`
  acknowledgements to reduce failover data-loss risk.
- Typed store failures distinguish lost leases, unavailable backends, malformed or foreign data,
  and mutations whose requested durability could not be confirmed.

### Changed
- Acquisition now returns an ownership lease. Completion, release, and heartbeat operations
  require that lease, fencing a stale worker after its lock has been stolen. Automatically managed
  JDBC schemas add the nullable `lease_id` ownership column when necessary.
- Redis polling uses a monotonic timeout with jittered exponential backoff, and purge work is
  bounded per invocation. Records include explicit owner and format markers, and namespace
  collisions fail closed without modifying foreign data.
- Redis and JDBC use backend server time for lock and expiry decisions.
- The JDBC constructor accepting an application `Clock` was removed because database time is now
  authoritative.
- Redis configuration now uses `RedisIdempotencyStoreConfig`; the default prefix is
  `idempotency4j:`. Positive sub-millisecond timeouts are rejected instead of being rounded to a
  Redis `WAIT` timeout of zero.
- A heartbeat scheduling failure releases the newly acquired lease before propagating.

### Upgrade notes

- A JDBC 0.1 to 0.2 deployment must use a coordinated stop/start because 0.1 workers do not honor
  lease fencing. Default schema management adds `lease_id` automatically. With `initSchema = false`,
  add a nullable `lease_id VARCHAR(36)` column through the application's schema-management tool.
- Redis has no migration path because it is new in this release. Development-snapshot records with
  another format are preserved and rejected.

### Security
- Documented Redis ACL, TLS, persistence, response-retention, and mandatory `noeviction`
  deployment requirements, plus the limits of Sentinel and application-level exactly-once
  guarantees.

## [0.1.0] - 2026-04-20

### Added
- Core idempotency engine (`IdempotencyEngine`) with lock lifecycle and heartbeat management
- `IdempotencyStore` SPI for pluggable persistence backends
- `AcquireResult` and `ExecutionResult` sealed outcome types
- `StoredResponse` for capturing and replaying HTTP responses
- `ResponseSanitizer` SPI for scrubbing sensitive fields before persistence
- In-memory provider (`idempotency-inmemory`) for testing and single-node deployments
- JDBC provider (`idempotency-jdbc`) with MySQL and PostgreSQL support
- Spring Web filter (`IdempotencyFilter`) with `@Idempotent` annotation
- Spring Boot auto-configuration (`idempotency-spring-boot-starter`)
- `IdempotencyStoreContract` shared test suite for store implementations
- GitHub Actions CI pipeline
- Apache 2.0 license headers on all source files
- Maven enforcer rules requiring Java 21+ and Maven 3.9+

[0.2.0]: https://github.com/josipmusa/idempotency4j/releases/tag/v0.2.0
[0.1.0]: https://github.com/josipmusa/idempotency4j/releases/tag/v0.1.0
