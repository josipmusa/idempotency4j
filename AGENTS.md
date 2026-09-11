# AGENTS.md

This file provides guidance to coding agents (Claude Code, Codex, and others) when working with code in this repository. `CLAUDE.md` is a symlink to this file - edit this one.

## What this is

idempotency4j is a Java idempotency library published to Maven Central: clients send an `Idempotency-Key` header, and duplicate requests normally get the stored response replayed without rerunning the annotated endpoint. Same key with a different request body is rejected (fingerprint mismatch). A record is identified by an `IdempotencyIdentity(scope, key)`, never by the key alone: the HTTP filter scopes by handler method (`PaymentController.create`), so the same key sent to two endpoints is two records. Storage is pluggable (JDBC, Redis, in-memory); the Spring integration is Servlet-only (no WebFlux). The library is not by itself an exactly-once guarantee for arbitrary downstream side effects.

## Verification

Before claiming any work is done, run:

```bash
./mvnw spotless:apply   # auto-fix formatting and license headers
./mvnw verify           # compile, all tests, Spotless check
```

`verify` includes the Spotless check and is the same command CI runs - if it passes locally, CI passes. JDBC and Redis provider tests use Testcontainers (MySQL, PostgreSQL, Redis), so Docker must be running.

Requires Java 21 (see `.sdkmanrc`) and always use the Maven wrapper `./mvnw`.

## Common commands

```bash
./mvnw test -pl idempotency-core -am                  # one module's tests
./mvnw test -pl providers/idempotency-jdbc -am        # JDBC provider (needs Docker)
./mvnw test -pl providers/idempotency-redis -am       # Redis provider (needs Docker)
./mvnw test -pl idempotency-core -am -Dtest=IdempotencyEngineTest            # one class
./mvnw test -pl idempotency-core -am -Dtest='IdempotencyEngineTest#When_*'   # one test
```

Always pass `-am` with `-pl` so upstream SNAPSHOT modules build from source.

## Architecture

Three layers with strict responsibility boundaries (documented in `IdempotencyEngine` and `IdempotencyStore` Javadoc - read those before touching the lifecycle):

- **Engine** (`idempotency-core`): transport- and framework-agnostic orchestrator - no HTTP types live here, so non-HTTP callers drive it directly. Calls `tryAcquire`, runs the action with a lock-extending heartbeat (fires at lockTimeout/2), calls `release` on failure. Does not decide *when* a key is complete - the adapter calls `engine.complete(...)` once it knows what to store, and the engine forwards that to `store.complete` and fires the lifecycle callbacks around it.
- **Adapter** (`spring/idempotency-spring-web`): `IdempotencyFilter` builds the context (scope from `IdempotentHandlerRegistry`, key from the header), invokes the engine, captures the HTTP response, and is the one that decides completion - through `engine.complete()` with the engine-provided lease, never `store.complete()` directly. Servlet translation (capture, replay, error bodies) lives in `HttpIdempotencyMapper`; HTTP-only settings live in `WebIdempotencyConfig` and `ResponseSanitizer`. Maps exceptions to HTTP: lock timeout -> 503, fingerprint mismatch -> 422.
- **Store** (`providers/*`): implements the `IdempotencyStore` SPI. All blocking, waiting, and stale-lock stealing happens inside `tryAcquire` - the engine never polls or retries.

Record state machine (in `IdempotencyStore` Javadoc), per identity: new -> IN_PROGRESS -> COMPLETE, or IN_PROGRESS -> FAILED on error (reclaimable by the next `tryAcquire`). Expired IN_PROGRESS locks are stolen atomically. Every acquisition has a lease; complete, release, and heartbeat mutations must match it to fence stale owners. Stores key by the full identity: JDBC's primary key is `(scope, idempotency_key)`, Redis uses `<prefix>rec:<scope>:<key>`, in-memory maps by `IdempotencyIdentity`.

Completion stores an `IdempotencyPayload`: a `StoredResponse` for HTTP, or `NoPayload` when there is nothing to replay. The request fingerprint is optional; two acquisitions mismatch only when both carry one and they differ.

`IdempotencyLifecycleListener` observers fire synchronously on the calling thread, in registration order, and cannot affect store state, the return value, or a propagated exception. The load-bearing invariant is one terminal callback (`onCompleted` XOR `onFailed`) per acquired lease, always preceded by `onAcquired` - consumers unbind thread-local state there, so any new engine path that acquires a lease must fire the pair or fire neither.

### Module dependency rules

Enforced by design, not tooling - do not violate them:

- `idempotency-core`: zero framework dependencies.
- `providers/*`: depend on core only, no Spring.
- `spring/idempotency-spring-web`: core + Spring Web.
- `spring/idempotency-spring-boot-starter`: spring-web module + providers, autoconfiguration only.

### The store contract

`IdempotencyStoreContract` in `idempotency-test` is the single source of truth for store behavior. Every `IdempotencyStore` implementation must extend it and pass all of it. New store = implement the SPI, extend the contract, implement `store()`. Behavior changes to stores belong in the contract first so every backend is held to them.

## Conventions

- Test naming: `When_<Context>_Expect_<Result>`.
- Formatting is Palantir Java Format via Spotless; license headers are inserted by `spotless:apply`, never by hand.
- Keep Javadoc on public API valid - release builds (`-Prelease`) run doclint and fail on malformed Javadoc.
