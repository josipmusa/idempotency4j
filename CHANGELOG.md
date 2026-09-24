# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- An autonomous completion inside a transaction now waits for that transaction. The record is
  written after the commit, on the store's own connection, and `onCompleted` fires then; a rollback
  releases the lease and fires `onFailed(..., ROLLBACK)`, so the key is retryable at once. Before,
  the completion quietly joined whatever transaction was bound to the thread: `onCompleted` fired
  before the commit, and a rollback left the record in progress until its lease expired. This
  applies to every store, so the starter now wires `SpringTransactionParticipation` whatever the
  store, and the engine no longer rejects a `TransactionParticipation` for a store that cannot join
  a transaction. One consequence: calling the same key twice inside one transaction, such as a
  duplicate within a batch processed in a single transaction, no longer replays the first result.
  The first record is still in progress when the second call arrives, so that call waits out its
  `waitTimeout` and then reports in flight.
- **Breaking for custom stores:** joined completion calls the new
  `IdempotencyStore.completeInTransaction`, and `complete` always means an autonomous write. The
  default implementation throws, so a store that cannot enlist in a transaction needs no change; a
  custom transactional store must override it. `ConnectionResolver.Operation` gains
  `COMPLETE_IN_TRANSACTION`, the one operation `TransactionAwareConnectionResolver` now runs on the
  transaction-bound connection - `COMPLETE` gets a fresh one like everything else.
  `TransactionalStoreContract` exercises `completeInTransaction` and adds the inverse cases for
  `complete`.
- `idempotency.completion-mode` is documented as applying to `@Idempotent` methods only. The HTTP
  filter always completed on its own and still does.

### Fixed

- A joined completion that the store refuses now always propagates, even under `LOG_AND_RETURN` -
  the starter's default - which used to return `Executed` and let the transaction commit the business
  writes without the inbox record. When that transaction rolls back, the lease is released, so the
  key is retryable at once instead of staying in flight until the lease expires.
- The JDBC store no longer waits past `waitTimeout` for a record whose row another transaction holds,
  typically a joined completion that has not committed yet. Every acquire statement now runs under a
  query timeout drawn from the remaining wait budget, and a caller that runs out answers in flight.
  JDBC counts query timeouts in whole seconds, so the wait can overshoot by up to one second. Before,
  such a caller blocked until the other transaction finished, without limit on PostgreSQL, and H2's
  own lock timeout surfaced as `IdempotencyStoreUnavailableException`.

## [0.4.0] - 2026-09-15

### Changed

- **Breaking: Spring Boot 4 and Spring Framework 7 are now the baseline.** The adapters and the
  starter are built against Spring Boot 4.0.8 and support the 4.0 and 4.1 lines. Spring Boot 3 is
  no longer supported: 3.5 reached open source end of life on 30 June 2026, and 3.5.16 was its
  final OSS patch. Boot 3 applications should stay on 0.3.0, which remains on Maven Central.
  Nothing in this library's own API changed - for an application already on Boot 4, upgrading is a
  version bump.
- Java 25 is now tested alongside Java 21. The compile baseline stays at Java 21, so Java 21
  applications are unaffected; the CI matrix covers Java 21 and 25 against Spring Boot 4.0 and 4.1.
- The Spring modules use JSpecify's `@Nullable` and `@NonNull` instead of the
  `org.springframework.lang` annotations that Spring Framework 7 deprecates. `idempotency-core` and
  the providers are unchanged.

## [0.3.0] - 2026-09-15

This release reworks the library around a transport-neutral engine. Most public types changed
shape, both storage layouts changed, and there is no data migration: see the upgrade notes at the
end of this section before upgrading a running deployment.

### Added

- `idempotency-spring`, a Spring module with nothing HTTP in it. `@Idempotent(key = "#event.id()")`
  now works on any Spring bean method through an AOP advisor, so an event listener, a Kafka
  consumer or a service method gets the same guarantee as an endpoint without a servlet on the
  classpath. `key` is a SpEL expression over the parameters, `scope` defaults to
  `<simple class name>.<method name>`, and `codec` names a `PayloadCodec` bean for a method that
  returns a value. A method that finds its key in flight throws `IdempotencyInFlightException`
  carrying `retryAfter`, so a broker redelivers instead of a consumer thread blocking;
  `OutcomeMapper` is the seam for a different answer.
- Completion can join the caller's transaction. With `CompletionMode.JOIN_TRANSACTION` on the
  context, `completion = "join-transaction"` on the annotation, or
  `idempotency.completion-mode=join-transaction` application-wide, the engine records the
  completion inside the transaction the action is running in, so the record and the business writes
  commit or roll back together. The pieces: `TransactionParticipation` in core (fifth engine
  constructor argument, `none()` by default), `SpringTransactionParticipation` and
  `TransactionAwareConnectionResolver` in `idempotency-spring`, `ConnectionResolver` on the JDBC
  store, `IdempotencyStore.supportsTransactionalCompletion()` (JDBC yes, in-memory and Redis no),
  `FailurePhase.ROLLBACK`, and `TransactionalStoreContract` in `idempotency-test`.
- `IdempotencyLifecycleListener` observes the idempotent boundary: `onAcquired`, `onCompleted`,
  `onFailed`, `onDuplicate` and `onInFlight`, synchronous on the calling thread, exactly one
  terminal callback per acquired lease. The starter wires every listener bean and honours `@Order`.
- `CompletionFailurePolicy` on `IdempotencyConfig` decides what happens when the action ran but the
  store refused the completion: `PROPAGATE` (default) or `LOG_AND_RETURN`. The starter uses
  `LOG_AND_RETURN` so a handler's response still reaches the client.
- The starter builds the store. With `idempotency-jdbc` and a single `DataSource` present a
  `JdbcIdempotencyStore` is wired, complete with the connection resolver joined completion needs;
  `idempotency.store-type` (`auto`, `jdbc`, `in-memory`, `none`) overrides the detection, and a
  store bean the application declares always wins. `auto` never falls back to in-memory. The
  selected store is logged at startup, and a `store-type` that names a backend that cannot be built
  fails the context. Redis stays hand-wired because it needs a raw Lettuce connection.
- `idempotency.jdbc.initialize-schema` (`embedded` by default, `always`, `never`), following the
  convention Boot uses for Session and Quartz. A real database no longer receives DDL from a
  library; point a migration at the schema file shipped in the provider jar. If the table cannot
  be queried after startup, the starter logs a warning naming those files.
- Startup validation for things that used to surface on the first call: an unparseable duration or
  unknown `completion` value, `key`, `codec` or `completion` on an HTTP handler, a value-returning
  method without a `codec`, joined completion against a store that cannot support it, and a
  `@Transactional` method with joined completion whose transaction advisor is not ordered ahead of
  the idempotency advisor.
- The JDBC store resolves a dialect from the database product name and runs on H2, and on any
  database it has no dialect for, with the PostgreSQL schema file and standard SQL. The store
  contract runs on H2 in the build alongside PostgreSQL and MySQL.
- New starter properties: `idempotency.completion-mode`, `idempotency.completion-failure-policy`,
  `idempotency.store-type`, `idempotency.jdbc.initialize-schema`, `idempotency.web.required` and
  `idempotency.web.in-flight-status`.

### Changed

- **Breaking.** The engine owns completion. `IdempotencyEngine.execute(ctx, ThrowingSupplier<T>,
  PayloadCodec<T>)` acquires, runs, encodes, records and returns a sealed `Outcome<T>`:
  `Executed(value)`, `Replayed(value, completedAt)` or `InFlight(retryAfter)`. The
  `execute(ctx, ThrowingRunnable)` overload stays for an action with nothing to replay. An in-flight
  key is an outcome to switch on, not an exception to catch.
- **Breaking.** A record is identified by `IdempotencyIdentity(scope, key)`, never by the key alone.
  The HTTP filter scopes by handler method (`PaymentController.create`), so the same key sent to two
  endpoints is two records. A scope is at most 128 characters and may not contain `':'`.
  `IdempotencyStore.complete`, `release` and `extendLock` take an identity where they took a key.
- **Breaking.** `lockTimeout` is split into `leaseDuration` (how long an acquisition is protected,
  default 30s, heartbeat at half) and `waitTimeout` (how long `tryAcquire` blocks for someone else's
  record, default 10s, zero means do not block). This renames `IdempotencyConfig` accessors, the
  `@Idempotent` attributes (`lease`, `waitTimeout`) and the starter properties.
- **Breaking.** The stored value is a transport-neutral `Payload(type, body, attributes)` with a
  `PayloadCodec<T>` translating an adapter's own type. `StoredResponse` and `ResponseSanitizer`
  move from core to `io.github.josipmusa.idempotency.spring.web`, where `StoredResponseCodec` stores
  an HTTP response as type `http/response` and applies the sanitizer inside `encode`. When a record
  completed is the store's to stamp: `AcquireResult.Duplicate(payload, completedAt)`.
- **Breaking.** `IdempotencyContext` is built through `IdempotencyContext.builder(scope, key)`; the
  public constructors are gone. The fingerprint is optional, and two acquisitions mismatch only when
  both carry one and they differ. `AcquireResult.LockTimeout` becomes `InFlight(retryAfter)`.
- **Breaking.** A failed attempt leaves no trace. The `FAILED` state is gone, `release` deletes the
  record, and the engine releases on any `Throwable`, `Error` included. `purgeExpired` deletes a
  record only when its TTL has passed and nobody owns it, so a lease that is still being heartbeated
  is never collected.
- **Breaking.** `@Idempotent` moves to `io.github.josipmusa.idempotency.spring`. `required` leaves
  the annotation for `WebIdempotencyConfig.required()` (`idempotency.web.required`), one answer for
  the whole API. Every attribute except `key` and `scope` is a string whose empty value means "use
  the configured default", `completion` included.
- **Breaking.** Core no longer models HTTP. `IdempotencyConfig.keyHeader` moves to
  `WebIdempotencyConfig`, and `IdempotencyFilter` is constructed from the engine and a
  `WebIdempotencyConfig` rather than a store, an `IdempotencyConfig` and a `Clock`.
- **Breaking.** A request whose key another caller still holds after `waitTimeout` gets `409` with
  `Retry-After` (whole seconds, rounded up, minimum 1) instead of a bare `503`; the status is
  `idempotency.web.in-flight-status`. The filter stores the response before the body reaches the
  client, so a client that sees a response can rely on the record being durable.
- **Breaking.** Starter properties are regrouped: transport-neutral settings stay at the top level,
  HTTP-only ones move under `idempotency.web`, JDBC-only ones under `idempotency.jdbc`. The single
  autoconfiguration becomes five, each conditional on its own trigger, so a message-driven
  application gets an engine and an interceptor without a servlet filter.
- **Breaking.** The JDBC schema is rebuilt around the new model: `scope` joins the primary key,
  `lock_expires_at` becomes `lease_expires_at`, `response_*` become `payload_type`, `payload` and
  `attributes`, `request_fingerprint` becomes `fingerprint`, `expires_at` is `NOT NULL`, and the
  index is `idx_idempotency_expires` on `expires_at`. The store no longer adds `lease_id` to a
  pre-0.2 table on startup.
- **Breaking.** The Redis record lives at `<prefix>rec:<scope>:<key>`, its hash fields follow the
  payload model, and the format version is `2`, so a record written by 0.2.0 fails closed rather than
  being misread.
- The JDBC store reads and writes every timestamp as UTC and takes the clock from the database in
  UTC as well, so `completedAt` leaves the store as a real instant whatever the session time zone.
- `idempotency-core` depends on `slf4j-api` so the engine can report a misbehaving listener and a
  swallowed completion failure. It remains free of framework dependencies.
- `idempotency-jdbc`, `idempotency-redis` and `idempotency-spring-web` no longer depend on Jackson.
  The attributes column and the stored header map go through `AttributeJson` in core, which reads
  back everything earlier releases wrote, so the library no longer fixes a Jackson major version
  for the application and the providers depend on core alone.

### Removed

- `ExecutionResult` and `IdempotencyLockTimeoutException`, replaced by `Outcome` and
  `Outcome.InFlight`.
- `@Idempotent.required`, the `IdempotencyContext` record constructor, `StoredResponse.completedAt`
  and `IdempotencyContext.MAX_KEY_LENGTH` (now on `IdempotencyIdentity`).

### Fixed

- The JDBC store writes `created_at` itself rather than leaving it to the column default, which was
  evaluated in the session time zone while every other timestamp was bound as UTC.
- `InMemoryIdempotencyStore.tryAcquire` counts every pass against the wait budget. A steal that lost
  its race went round again without checking the deadline.

### Security

- Idempotency keys are masked in logs and exception messages. A key is client-controlled and may
  carry identifying data, so a record renders as its scope plus a short digest of the key,
  `PaymentController.create/#3f9a2c71`.

### Upgrade notes

- **Recreate the JDBC table.** There is no migration from the 0.2.0 layout. Drop
  `idempotency_records` and create it from the `idempotency-schema-postgresql.sql` or
  `idempotency-schema-mysql.sql` file in the `idempotency-jdbc` jar, or let
  `idempotency.jdbc.initialize-schema=always` do it. Stop the 0.2.0 instances first; the two layouts
  cannot share a table.
- **Discard Redis records.** Records written by 0.2.0 are rejected, not read. In-flight work at the
  moment of the switch is therefore not deduplicated across it.
- **Rename properties.** `idempotency.default-lock-timeout` becomes `idempotency.default-lease`
  and `idempotency.default-wait`; `key-header`, `max-body-bytes` and `filter-order` move under
  `idempotency.web`.
- **Update imports.** `@Idempotent` is in `io.github.josipmusa.idempotency.spring`;
  `StoredResponse` and `ResponseSanitizer` are in `io.github.josipmusa.idempotency.spring.web`.
  `@Idempotent(required = false)` becomes `idempotency.web.required=false`.
- **Joined completion in Spring Boot** needs the transaction advisor ordered ahead of the
  idempotency advisor: `@EnableTransactionManagement(order = Ordered.HIGHEST_PRECEDENCE)`. The
  context fails at startup with that instruction otherwise.
- **Callers driving the engine directly** replace `execute` plus `complete` with a single
  `execute(ctx, supplier, codec)` and switch on the returned `Outcome`.

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

[Unreleased]: https://github.com/josipmusa/idempotency4j/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/josipmusa/idempotency4j/releases/tag/v0.4.0
[0.3.0]: https://github.com/josipmusa/idempotency4j/releases/tag/v0.3.0
[0.2.0]: https://github.com/josipmusa/idempotency4j/releases/tag/v0.2.0
[0.1.0]: https://github.com/josipmusa/idempotency4j/releases/tag/v0.1.0
