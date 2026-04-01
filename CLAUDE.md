# idempotency4j

A pure Java idempotency library with pluggable storage backends
and a Spring Boot adapter.

## Project structure

```
idempotency4j/
├── idempotency-core/        Pure Java. No framework dependencies.
│                            Engine, SPI interfaces, domain models,
│                            exception hierarchy.
├── idempotency-test/        Test utilities. Ships as a real artifact
│                            consumed with <scope>test</scope>.
│                            Contains IdempotencyStoreContract only.
│                            Depends on idempotency-memory for the
│                            in-memory store test double.
├── providers/
│   ├── idempotency-inmemory/  In-memory implementation of IdempotencyStore.
│   │                        Production usable for single-instance deployments
│   │                        and local development. Not suitable for
│   │                        horizontally scaled environments.
│   ├── idempotency-jdbc/    JDBC implementation of IdempotencyStore.
│   │                        Depends on core only. No Spring dependency.
│   └── idempotency-redis/   Redis implementation of IdempotencyStore.
│                            Depends on core only. No Spring dependency.
├── idempotency-spring-web/      Spring MVC adapter. Depends on core +
│                            Spring Web. Contains @Idempotent annotation
│                            and filter.
└── idempotency-spring-boot-starter/  Autoconfiguration. Wires everything
                             together based on classpath detection.
```

## Module dependency rules

These rules are strict. Do not violate them.

- `idempotency-core` has zero framework dependencies
- `idempotency-inmemory` depends on core only
- `idempotency-jdbc` depends on core only — no Spring
- `idempotency-redis` depends on core only — no Spring
- `idempotency-test` depends on idempotency-inmemory + JUnit 5 + AssertJ
- `idempotency-spring-web` depends on core + Spring Web
- `idempotency-spring-boot-starter` depends on spring module + providers

When adding dependencies to pom.xml - first think about where the version managing belongs.
Ask yourself if it makes sense for the version management to be in the parent pom.xml under dependency management.
Never provide version in the actual dependency declaration - all dependency versions should be declared under <properties>
and then referenced in the actual dependency declaration.

## Core architecture

The library is built around three responsibilities kept strictly separate:

- **Engine** (`IdempotencyEngine`) — orchestrates the lock lifecycle and
  heartbeat. Knows nothing about HTTP, databases, or frameworks.
- **Store** (`IdempotencyStore`) — SPI interface. Handles persistence and
  in-flight blocking. Implemented by jdbc/redis modules.
- **Adapter** — framework-specific layer (Spring interceptor). Translates
  HTTP request into `IdempotencyContext`, captures HTTP response, calls
  `store.complete()`.

## Key contracts

### `IdempotencyEngine`

Takes a fully resolved `IdempotencyContext` and a `ThrowingRunnable`.
Returns `ExecutionResult` — either `Executed` or `Duplicate`.

The engine does NOT call `store.complete()`. That is the adapter's
responsibility. The engine only calls `store.release()` on action failure
and `store.extendLock()` via the heartbeat.

### `IdempotencyStore`

Four methods:

```java
AcquireResult tryAcquire(IdempotencyContext context);
void complete(String key, StoredResponse response, Duration ttl);
void release(String key);
void extendLock(String key, Duration extension);
int purgeExpired();
```

`tryAcquire` is responsible for all blocking logic internally.
The engine never polls or waits — it calls `tryAcquire` once and
gets back a resolved result.

### `purgeExpired()`

Every `IdempotencyStore` implementation must implement `purgeExpired()`.
It removes all records that are no longer needed:

- `COMPLETE` records whose `expires_at` is in the past
- `FAILED` records whose `expires_at` is in the past
- `IN_PROGRESS` records whose both `lock_expires_at` and `expires_at`
  are in the past

Stale `IN_PROGRESS` records whose lock has expired but TTL has not
are intentionally left in place — they are still eligible for lock
stealing by the next `tryAcquire` caller.

Scheduling is never the store's responsibility. The Spring Boot starter
registers a `SchedulingConfigurer` bean that adds a cron task calling
this method using the expression defined by `idempotency.purge.cron`
(default: `"0 0 * * * *"`, hourly). The configurer is inert if the
application has not enabled `@EnableScheduling`. In plain Java usage,
the caller schedules it manually with a
`ScheduledExecutorService`.

No store implementation may self-schedule — no internal reaper threads.
Cleanup is always driven externally.

### `IdempotencyContext`

Fully resolved at construction. No optionals, no nulls.
Built by the adapter using `IdempotencyConfig` defaults +
annotation/request values before being passed to the engine.

Fields: `key`, `ttl`, `lockTimeout`, `requestFingerprint`.
The `requestFingerprint` is a SHA-256 hex digest of the request body,
computed by the adapter layer (`RequestFingerprint.of(body)`).

### `IdempotencyConfig`

Holds defaults (ttl, lockTimeout, keyHeader, keyRequired).
Used by adapters to resolve `IdempotencyContext`. Not used by the engine.

If you find yourself adding a Spring dependency to core or a provider
module, stop. The design is wrong.

## State machine for a key

```
[not exists] ──tryAcquire──→ IN_PROGRESS ──complete()──→ COMPLETE
                                  │
                              release()
                                  │
                                  ↓
                               FAILED ──tryAcquire──→ IN_PROGRESS
```

COMPLETE keys return `Duplicate` on subsequent `tryAcquire` calls
until TTL expires, after which they are treated as new.

COMPLETE keys return `FingerprintMismatch` when the incoming request
fingerprint differs from the one stored with the original request.

IN_PROGRESS keys whose `lockExpiresAt` is in the past are considered
stale and can be stolen by the next `tryAcquire` caller.

## Heartbeat

The engine starts a heartbeat when it acquires the lock. The heartbeat
calls `store.extendLock()` at 50% of the lockTimeout interval.

Example: lockTimeout = 10s → heartbeat fires every 5s.

The heartbeat stops in the `finally` block whether the action succeeds
or throws. `extendLock` silently ignores calls for unknown or
non-IN_PROGRESS keys — heartbeat firing after completion is expected
and must not throw.

## Test conventions

### Naming

All test methods follow the `When_<Context>_Expect_<Result>` pattern:
```
When_NewKey_Expect_ReturnsAcquired
When_CompletedKey_Expect_ReturnsDuplicateWithCorrectResponse
When_StaleLock_Expect_IsStolen
```

### Contract tests

Every `IdempotencyStore` implementation must extend
`IdempotencyStoreContract` from `idempotency-test` and implement
`store()` to provide the implementation under test.

```java
class JdbcIdempotencyStoreTest extends IdempotencyStoreContract {
    @Override
    protected IdempotencyStore store() {
        return new JdbcIdempotencyStore(dataSource);
    }
}
```

The contract test suite is the single source of truth for store
behavior. If a case is added to `IdempotencyStoreContract`, all
implementations are automatically tested against it.

### Test doubles

Use `InMemoryIdempotencyStore` from `idempotency-inmemory` for any
test that needs a store without infrastructure.

Use Testcontainers for `JdbcIdempotencyStore` and
`RedisIdempotencyStore` integration tests.

Never mock `IdempotencyStore` in concurrency tests — mocks cannot
reproduce the blocking and lock-stealing behavior that the contract
requires.

## What not to do

- Do not add logic to the engine that belongs in the store
- Do not add logic to the adapter that belongs in the engine
- Do not add Spring dependencies to core, jdbc, or redis modules
- Do not call `store.complete()` from the engine
- Do not make `extendLock` throw when the key is missing or not IN_PROGRESS
- Do not skip the contract tests for a new store implementation
- Do not use `Optional` in `IdempotencyContext` — context must be
  fully resolved before reaching the engine

## Running tests

**Run a specific module's tests:**
```bash
mvn test -pl providers/idempotency-inmemory -am
```

The `-am` flag (also-make) builds upstream sibling modules first so SNAPSHOT dependencies resolve.

**Run all tests:**
```bash
mvn verify
```

## Git workflow

When the user mentions committing, pushing, or creating a PR/MR, follow this flow exactly:

### 1. Establish a base branch
- Ask the user what the base branch should be.
- If the user provides one: confirm it exists locally, then pull to get a clean start.
- If the user doesn't provide one: default to `main`.

### 2. Create a feature branch
- From the confirmed base branch, create a new branch locally.
- Name it sensibly based on the work being done (e.g. `feat/redis-provider`, `fix/lock-timeout-validation`).
- Never commit or push directly to `main`/`master`.

### 3. Commit, push, and create PR

**Commit messages:** One short sentence per logical change. No long paragraphs, no bullet lists in the subject line.
```
Fix deadline bypass in InMemoryIdempotencyStore under contention
Add failedKeyUnderContention contract test
```

Push with `git push -u origin <branch>` and create the PR with `gh pr create`.

### What never to commit or push
- Files generated during AI analysis or used only to complete a task (e.g. anything under `docs/superpowers/`, temporary plan files, scratch notes).
- Secrets, credentials, or `.env` files.

## Instruction updating
When you change any code, make sure to analyze if this document needs any changing and apply the changes.
This includes the explanations and the current implementation status.

## Request fingerprinting

Every request's body is hashed (SHA-256) and stored alongside the
idempotency key. When a subsequent request arrives with the same key
but a different body hash, the store returns `FingerprintMismatch`
instead of `Duplicate`, and the adapter returns HTTP 422.

- **Computed in:** `RequestFingerprint.of(byte[] body)` in `idempotency-spring-web`
- **Stored in:** `request_fingerprint VARCHAR(64)` column (JDBC) or
  `requestFingerprint` field on `Entry` record (in-memory)
- **Compared at:** store level in `tryAcquire()` when status is COMPLETE
- **Engine mapping:** `FingerprintMismatch` → `IdempotencyFingerprintMismatchException`
- **HTTP response:** 422 with `{"error": "Idempotency-Key reused with a different request body"}`

## Security considerations

The idempotency store persists full HTTP response bodies and headers.
This may include sensitive data such as PII, authentication tokens,
or financial information.

For compliance-sensitive environments:
- Enable encryption at rest on the backing database
- Use short TTL values to limit the retention window of cached responses
- Consider the scheduled purge feature (`idempotency.purge.cron`) to
  remove expired entries promptly
- Audit which endpoints are annotated with `@Idempotent` and whether
  their responses contain sensitive data

## Current implementation status

### Complete
- idempotency-core (engine + unit tests with Mockito)
- providers/idempotency-inmemory (InMemoryIdempotencyStore)
- providers/idempotency-jdbc (JdbcIdempotencyStore with Testcontainers MySQL tests)
- idempotency-test (IdempotencyStoreContract)
- idempotency-spring-web (IdempotencyFilter + @Idempotent annotation)
- idempotency-spring-boot-starter (IdempotencyAutoConfiguration + IdempotencyPurgeAutoConfiguration)

### Not started — do not implement
- providers/idempotency-redis