# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- `@Idempotent` on a Spring MVC handler no longer fails the context. The method advisor claimed
  request mapping handlers too and then demanded the `key` expression an endpoint has no use for,
  so the documented HTTP usage could not start in any application that had both halves of the
  starter active - which is every Servlet application. Handlers are now left to the filter, which
  also stops a keyed endpoint being guarded twice under two different keys. The attributes only a
  method can honour, `key`, `codec` and `completion`, are rejected on a handler at startup instead
  of being silently ignored.
- `@Idempotent(completion = "join-transaction")` against a store that cannot complete inside a
  caller's transaction now fails at startup. Only the application-wide
  `idempotency.completion-mode` was checked before, so a per-method request surfaced on the first
  call as a claim that no transaction was active - inside a method that was demonstrably
  `@Transactional`. The engine's own runtime check names the store's limitation too, rather than
  blaming the caller for a transaction they did open.
- A transaction whose outcome Spring reports as `STATUS_UNKNOWN` no longer leaves a lease with no
  terminal lifecycle callback at all. `SpringTransactionParticipation` matched commit and rollback
  by exact status, so an indeterminate completion fired neither, and a listener that had bound
  state in `onAcquired` never unbound it. Anything that is not a confirmed commit is now resolved
  as a rollback.
- `InMemoryIdempotencyStore.tryAcquire` counts every pass against the wait budget. A steal that
  lost its race retried through a `continue` that skipped the deadline check, so a caller could go
  round again after its budget was spent.
- The JDBC store writes `created_at` itself rather than leaving it to the column default. The
  default is evaluated in the session's time zone while every other timestamp is bound as UTC, so
  a row could record a `created_at` hours away from its own `expires_at`.

### Security

- Idempotency keys are masked in logs and exception messages. A key is client-controlled and may
  carry identifying data, and `IdempotencyIdentity.toString()` put it verbatim into store, engine
  and rollback exception messages, which the default completion-failure policy logs at error
  level. A record now renders as its scope plus a short stable digest of the key.

### Changed

- **Breaking.** Starter properties are regrouped. Transport-neutral settings stay at the top level,
  HTTP-only ones move under `idempotency.web` (`key-header`, `required`, `in-flight-status`,
  `max-body-bytes`, `filter-order`), and JDBC-only ones under `idempotency.jdbc`. New:
  `idempotency.completion-mode`, `idempotency.store-type`, `idempotency.jdbc.initialize-schema`,
  `idempotency.web.required` and `idempotency.web.in-flight-status`, the last two previously
  reachable only by declaring a `WebIdempotencyConfig` bean.
- **Breaking.** The starter's single autoconfiguration is split into five, each conditional on its
  own trigger: store detection, the transport-neutral engine, the HTTP filter (Servlet web
  applications only), the method interceptor (Spring AOP only), and the purge scheduler. A
  message-driven application now gets an engine and an interceptor without a servlet filter it has
  no use for.
- **Breaking.** `@Idempotent(completion = ...)` is a `String` rather than a `CompletionMode`.
  `"autonomous"` and `"join-transaction"` are matched case-insensitively with `-` and `_`
  interchangeable, an unrecognised value is rejected at startup, and empty means "use
  `IdempotencyConfig.defaultCompletionMode()`" - the convention `ttl`, `lease` and `waitTimeout`
  already followed. An enum attribute could not express "unset", which left the new
  `idempotency.completion-mode` property unable to reach an annotated method.
- `IdempotencyConfig` gains `defaultCompletionMode` (default `CompletionMode.AUTONOMOUS`), read by
  the adapter layer while it builds a context. The engine still reads only
  `completionFailurePolicy` from that class.
- The `record-lifecycle` and `request-outcomes` diagrams are redrawn for the current model: the
  `FAILED` state is gone, releasing deletes the row and returns the key to absent, an abandoned
  in-progress record is purged only once its TTL has elapsed **and** its lease has expired, and an
  in-flight key is answered with 409 plus `Retry-After` rather than a bare 503.
- README and `AGENTS.md` are rewritten for the library as it now is: core usage, HTTP, method-level
  idempotency, joined completion, and store selection.

- **Breaking.** New `idempotency-spring` module holding the Spring integration that is not about
  HTTP: `SpringTransactionParticipation`, a `TransactionAwareConnectionResolver` that runs
  `COMPLETE` on the caller's transaction and everything else on a connection of its own, a
  method-level `@Idempotent`, and the AOP interceptor behind it. An event listener, a
  `@KafkaListener` method, and a plain service method now get the same integration without pulling
  in Servlet.
- **Breaking.** `@Idempotent` moves from `io.github.josipmusa.idempotency.spring.web` to
  `io.github.josipmusa.idempotency.spring` and gains `key` (a SpEL expression over the method's
  parameters, required for method-level use), `scope` (empty means `<simple class
  name>.<method name>`), `completion`, and `codec` (the name of a `PayloadCodec` bean, required for
  a method that returns a value). `required` leaves the annotation.
- **Breaking.** Whether a request without an idempotency key is rejected is now
  `WebIdempotencyConfig.required()`, an application-wide setting defaulting to `true`, rather than a
  per-handler annotation attribute. `IdempotentHandlerRegistry.ResolvedIdempotent` loses its
  `required` component.
- **Breaking.** A scope may not contain `':'`. Stores compose scope and key into one string, and the
  composition is only unambiguous while the scope has no separator in it - without the restriction a
  client-controlled key could reach across scopes. Keys may still contain colons.
- An intercepted method that finds the key already in flight throws the new
  `IdempotencyInFlightException`, which carries `retryAfter`, so a broker redelivers instead of a
  consumer thread blocking. `OutcomeMapper` is the seam for callers that want a different answer.

- **Breaking.** Completion can join the caller's transaction. `IdempotencyContext` gains
  `completionMode()` - `CompletionMode.AUTONOMOUS` (the default) or `JOIN_TRANSACTION` - and
  `IdempotencyEngine` gains a fifth constructor argument, a `TransactionParticipation`
  (`TransactionParticipation.none()` by default). Under `JOIN_TRANSACTION` the engine records the
  completion inside the transaction the action is already running in, so the inbox record and the
  business writes commit together: a crash before the commit leaves neither, a crash after it
  leaves both. The terminal callback moves with the record - `onCompleted` fires after the commit,
  and a rollback releases the lease and fires the new `FailurePhase.ROLLBACK` instead. A joined
  context without an active transaction is an `IllegalStateException`, and giving a real
  `TransactionParticipation` to a store that cannot support it is an `IllegalArgumentException` at
  construction.
- `IdempotencyStore` gains `supportsTransactionalCompletion()`, default `false`. `JdbcIdempotencyStore`
  returns `true`; the in-memory and Redis stores return `false`, Redis permanently.
- The JDBC store takes an optional `ConnectionResolver`, which decides the connection each
  operation runs on. Only `COMPLETE` is ever expected to get a transaction-bound one, and the store
  hands every connection back through the resolver instead of closing it, so it never closes or
  commits a connection it did not open. Without a resolver the store behaves exactly as before.
- `idempotency-test` gains `TransactionalStoreContract`, the second store contract. Only stores that
  report support for transactional completion extend it, and they must pass both contracts.

- **Breaking.** The engine owns completion. `IdempotencyEngine.execute` now takes the action as a
  `ThrowingSupplier<T>` plus a `PayloadCodec<T>` and returns a sealed
  `Outcome<T>` - `Executed(value)`, `Replayed(value, completedAt)`, or `InFlight(retryAfter)` -
  having already encoded the result and recorded the completion. The `execute(ctx,
  ThrowingRunnable)` overload stays for an action with nothing to replay. `IdempotencyEngine.complete`
  and `ExecutionResult` are deleted, and so is `IdempotencyLockTimeoutException`: an in-flight key is
  an outcome to switch on, not an exception to catch.
- **Breaking.** `IdempotencyConfig` gains `completionFailurePolicy`, a
  `CompletionFailurePolicy` of `PROPAGATE` (the default) or `LOG_AND_RETURN`, which decides what the
  engine does when the action ran but the store refused the completion. The lease is not released
  either way - the work happened, so the record must not be erased. `IdempotencyEngine` takes the
  config as an optional fourth constructor argument.
- **Breaking.** A request rejected because another caller holds the key now gets 409 with
  `Retry-After` in whole seconds (rounded up, minimum 1) instead of a bare 503. The status is
  configurable through the new `WebIdempotencyConfig.inFlightStatus()`, built with
  `WebIdempotencyConfig.builder()`.
- `IdempotencyLifecycleListener` gains `onInFlight(ctx, retryAfter)`, which fires without a lease
  like `onDuplicate`. The one-terminal-per-acquired-lease invariant is unchanged.
- The web filter stores the response before the body reaches the client, so a client that sees a
  response can rely on the record being durable. The starter configures its engine with
  `LOG_AND_RETURN` - overridable through `idempotency.completion-failure-policy` - so a storage
  failure still lets the handler's response through.

- **Breaking.** A failed attempt now leaves no trace. The `FAILED` state is gone, so a record is
  absent, `IN_PROGRESS` or `COMPLETE`, and `IdempotencyStore.release` deletes the record instead of
  relabelling it: the next `tryAcquire` for that identity sees a key that was never used. The engine
  releases on any `Throwable`, `Error` included, so a lease is no longer held until expiry when the
  action dies with something that is not an `Exception`.
- **Breaking.** The JDBC schema reaches its final shape: `status` narrows to `VARCHAR(12)`,
  `expires_at` becomes `NOT NULL`, the columns are reordered, and the
  `(status, expires_at, lease_expires_at)` index is replaced by `idx_idempotency_expires` on
  `expires_at` alone. `JdbcIdempotencyStore` no longer migrates a pre-lease-fencing table by adding
  `lease_id` on startup. There is no migration; recreate the table.
- `purgeExpired` now deletes a record only when `expires_at` has passed **and** nobody owns it: an
  `IN_PROGRESS` record also needs an expired lease. A record whose heartbeat is still running is
  never collected, whatever its age, so a purge can no longer free a key out from under a
  long-running action and let a second caller run it again.
- The JDBC store no longer reports a deadlock or lock-wait timeout during acquisition as
  `IdempotencyStoreUnavailableException`. Now that `release` deletes the record, acquiring races an
  INSERT against another caller's DELETE on the same primary key, and InnoDB breaks some of those
  races by rolling one side back. That is a lost race, so `tryAcquire` re-inspects the row within
  the caller's `waitTimeout` exactly as it already does for a duplicate key.
- **Breaking.** The stored value is a transport-neutral `Payload(String type, byte[] body,
  Map<String, String> attributes)` in core, with `Payload.none()` for a caller with nothing to
  replay and a `PayloadCodec<T>` (`encode`/`decode`, plus `PayloadCodec.none()`) for translating an
  adapter's own result type. `IdempotencyPayload` and `NoPayload` are deleted, and `StoredResponse`
  moves out of core to `io.github.josipmusa.idempotency.spring.web` alongside the new
  `StoredResponseCodec`, which stores an HTTP response under type `http/response` with the status
  and headers as attributes and the body as the payload body, and runs the configured
  `ResponseSanitizer` inside `encode`. `attributes` is returned verbatim on a duplicate, which is
  where a non-HTTP caller keeps correlation data such as the ids of the messages it published.
- **Breaking.** When a record completed is now the store's to determine, not the caller's.
  `StoredResponse` loses its `completedAt` component, and the instant travels alongside the payload
  instead: `AcquireResult.Duplicate(payload, completedAt)`, `ExecutionResult.Duplicate(payload,
  completedAt)` and `IdempotencyLifecycleListener.onDuplicate(ctx, payload, completedAt)`.
  `IdempotencyStore.complete` and `onCompleted` take a `Payload`. `IdempotencyFilter` no longer
  accepts a `Clock`, which had no remaining purpose.
- **Breaking.** The JDBC schema replaces `response_code`, `response_headers` and `response_body`
  with `payload_type`, `payload` and `attributes` (JSON), and renames `request_fingerprint` to
  `fingerprint VARCHAR(128)`. The Redis hash replaces `code`, `headers` and `body` with
  `payload_type`, `payload` and `attributes`, and its format version moves to `2` so a record
  written under the previous layout fails closed rather than being misread. There is no migration;
  recreate the table and discard existing records.

### Fixed

- The JDBC store reads and writes every timestamp as UTC and takes the database clock from
  `UTC_TIMESTAMP` (MySQL) or `CURRENT_TIMESTAMP AT TIME ZONE 'UTC'` (PostgreSQL). Previously the
  driver was free to read a server-local `CURRENT_TIMESTAMP` in its own zone, which on MySQL shifted
  every timestamp by the JVM's offset. That was invisible while timestamps were only ever compared
  against other columns written the same way, but `completed_at` now leaves the store as an instant
  a caller sees.

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

- The starter builds an `IdempotencyStore` for you. With `idempotency-jdbc` on the classpath and a
  single `DataSource` in the context, a `JdbcIdempotencyStore` is wired up complete with the
  `TransactionAwareConnectionResolver` that joined completion needs - previously an application had
  to know the resolver existed for `JOIN_TRANSACTION` to do anything. `idempotency.store-type`
  (`auto`, `jdbc`, `in-memory`, `none`) overrides the detection, and a store bean the application
  declares itself always wins.

  `auto` deliberately never falls back to the in-memory store: an inbox that deduplicates only
  within one JVM and forgets on restart is not a property anything should acquire by accident.
  Naming a backend that cannot be built fails the context at startup rather than leaving the
  application silently un-deduplicated, and the selected store is logged at INFO.

  The Redis store is not autoconfigured. It takes a raw Lettuce
  `StatefulRedisConnection<String, byte[]>` rather than the `RedisConnectionFactory` Spring Boot
  produces, and bridging the two would mean either reaching into Spring Data Redis internals or
  reimplementing Boot's URL, Sentinel, SSL and pooling handling and then running two clients with
  two lifecycles.
- `idempotency.jdbc.initialize-schema` (`embedded` by default, plus `always` and `never`), following
  the convention Spring Boot uses for Session and Quartz. A development database gets its table for
  free while a real one no longer receives DDL from a library at startup; point a migration tool at
  the `idempotency-schema-postgresql.sql` or `idempotency-schema-mysql.sql` file shipped in the
  provider jar instead.
- `JdbcIdempotencyStore(DataSource, boolean initSchema, ConnectionResolver)`, the combination a
  hand-wiring Spring application needs, without having to restate the default poll interval.

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
