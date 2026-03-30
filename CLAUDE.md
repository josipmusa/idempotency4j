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
├── idempotency-spring/      Spring MVC adapter. Depends on core +
│                            Spring Web + Spring AOP. Contains
│                            @Idempotent annotation and interceptor.
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
- `idempotency-spring` depends on core + Spring Web + Spring AOP
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
```

`tryAcquire` is responsible for all blocking logic internally.
The engine never polls or waits — it calls `tryAcquire` once and
gets back a resolved result.

### `IdempotencyContext`

Fully resolved at construction. No optionals, no nulls.
Built by the adapter using `IdempotencyConfig` defaults +
annotation/request values before being passed to the engine.

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

All test methods follow the `Context_ExpectedResult` pattern:
```
newKey_returnsAcquired
completedKey_returnsDuplicateWithCorrectResponse
staleLock_isStolen
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

## Current implementation status

### Complete
- idempotency-core (engine + unit tests with Mockito)
- providers/idempotency-inmemory (InMemoryIdempotencyStore)
- providers/idempotency-jdbc (JdbcIdempotencyStore with Testcontainers MySQL tests)
- idempotency-test (IdempotencyStoreContract)
- idempotency-spring (IdempotencyFilter + @Idempotent annotation)

### Not started — do not implement
- providers/idempotency-redis
- idempotency-spring-boot-starter

claude --resume c1966137-a8ce-4a03-9a80-f251bf9953fe 