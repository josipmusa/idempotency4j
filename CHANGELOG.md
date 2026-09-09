# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Breaking.** `idempotency-core` no longer models HTTP. The engine, the store SPI and the
  context are transport-neutral, so a message listener or an event handler can drive them the
  same way the Servlet filter does.
- **Breaking.** `IdempotencyContext.requestFingerprint` is now optional. Build a context without
  one via `IdempotencyContext.withoutFingerprint(key, ttl, lockTimeout)`; `fingerprint()` returns
  it as an `Optional`. A blank string is still rejected - `null` is how a caller says "none".
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
  `defaultTtl` and `defaultLockTimeout`. The `idempotency.key-header` property is unchanged, and
  the starter registers a `WebIdempotencyConfig` bean from it. Applications constructing
  `IdempotencyFilter` by hand pass a `WebIdempotencyConfig` where they passed `IdempotencyConfig`.

### Added

- `NoPayload` for recording a completed operation that has nothing to replay, so non-HTTP callers
  can use the engine without fabricating a response.

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
