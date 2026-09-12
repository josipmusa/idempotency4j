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

- **Engine** (`idempotency-core`): transport- and framework-agnostic orchestrator that owns the entire lifecycle - no HTTP types live here, so non-HTTP callers drive it directly. `execute(ctx, ThrowingSupplier<T>, PayloadCodec<T>)` calls `tryAcquire`, runs the action with a lease-extending heartbeat (fires at leaseDuration/2), encodes the result, calls `store.complete`, and fires the lifecycle callbacks around all of it; `execute(ctx, ThrowingRunnable)` is the same for an action with nothing to replay. It calls `release` on any `Throwable` from the action, but never after a completion failure - the work happened, so the record must not be erased. There is no public `complete`: an adapter cannot hold a lease. The return is a sealed `Outcome<T>`: `Executed(value)`, `Replayed(value, completedAt)`, `InFlight(retryAfter)`.
- **Completion failures**: `CompletionFailurePolicy` on `IdempotencyConfig` (the only thing the engine reads from that class) decides what happens when the action succeeded but the store refused the completion. `PROPAGATE` (default) rethrows; `LOG_AND_RETURN` logs at error and returns `Executed` anyway. An encoding failure counts as a completion failure. Either way `onFailed(..., COMPLETION)` fires, so the one-terminal-per-lease invariant holds.
- **Adapter** (`spring/idempotency-spring-web`): `IdempotencyFilter` builds the context (scope from `IdempotentHandlerRegistry`, key from the header), hands the engine a supplier that runs the chain into a `ContentCachingResponseWrapper` and returns the captured `StoredResponse`, then switches on the `Outcome`: `Executed` flushes the buffered body (so the record is durable before the client sees anything), `Replayed` writes the stored response with `Idempotent-Replayed: true`, `InFlight` answers `WebIdempotencyConfig.inFlightStatus()` (409 by default) with `Retry-After` in whole seconds, rounded up, minimum 1. Servlet translation (capture, replay, error bodies) lives in `HttpIdempotencyMapper`; HTTP-only settings live in `WebIdempotencyConfig` and `ResponseSanitizer`. Fingerprint mismatch is the one exception it still catches -> 422. The starter configures the filter's engine with `LOG_AND_RETURN` (`idempotency.completion-failure-policy`).
- **Store** (`providers/*`): implements the `IdempotencyStore` SPI. All blocking, waiting, and stale-lease stealing happens inside `tryAcquire` - the engine never polls or retries. `tryAcquire` blocks for at most the context's `waitTimeout` (zero means do not block) and reports `AcquireResult.InFlight(retryAfter)` when it gives up, where `retryAfter` is the holder's remaining lease.

Record state machine (in `IdempotencyStore` Javadoc), per identity: a record is absent, IN_PROGRESS, or COMPLETE - two states, not three. absent -> IN_PROGRESS -> COMPLETE, and IN_PROGRESS -> absent on error, because `release` deletes the record: a failed attempt leaves no trace and the next `tryAcquire` sees a key that was never used. The engine releases on any `Throwable`, `Error` included. Expired IN_PROGRESS leases are stolen atomically. Every acquisition has a lease; complete, release, and heartbeat mutations must match it to fence stale owners. `purgeExpired` deletes the records whose `expires_at` has passed and that nobody owns: an IN_PROGRESS record also needs an expired lease. The two timestamps answer different questions - `expires_at` is how long a completed record stays replayable, the lease is whether a caller still owns the key - and purge must ask both. Dropping a record whose lease is still being heartbeated would let a second caller rerun the protected action. An expired lease on its own marks a record as stealable, not as garbage. Stores key by the full identity: JDBC's primary key is `(scope, idempotency_key)`, Redis uses `<prefix>rec:<scope>:<key>`, in-memory maps by `IdempotencyIdentity`.

Completion stores a `Payload(type, body, attributes)` - a transport-neutral envelope core never interprets, with `Payload.none()` for a caller with nothing to replay. A `PayloadCodec<T>` translates an adapter's own result type to and from it: `StoredResponseCodec` in `idempotency-spring-web` stores an HTTP response as type `http/response` with status and headers in `attributes`, and applies the `ResponseSanitizer` inside `encode`. `attributes` is flat `Map<String, String>`, returned verbatim on a duplicate, and is where a non-HTTP caller puts correlation data such as outgoing publication ids. When a record completed is the store's to determine, not the caller's: it stamps `completedAt` and reports it on `AcquireResult.Duplicate` alongside the payload. The request fingerprint is optional; two acquisitions mismatch only when both carry one and they differ.

`IdempotencyLifecycleListener` observers fire synchronously on the calling thread, in registration order, and cannot affect store state, the return value, or a propagated exception. The load-bearing invariant is one terminal callback (`onCompleted` XOR `onFailed`) per acquired lease, always preceded by `onAcquired` - consumers unbind thread-local state there, so any new engine path that acquires a lease must fire the pair or fire neither. `onDuplicate` and `onInFlight` stand alone: no lease was acquired, so neither `onAcquired` nor a terminal callback accompanies them.

### Module dependency rules

Enforced by design, not tooling - do not violate them:

- `idempotency-core`: zero framework dependencies.
- `providers/*`: depend on core only, no Spring.
- `spring/idempotency-spring-web`: core + Spring Web, plus Jackson for the header map `StoredResponseCodec` carries in a payload attribute.
- `spring/idempotency-spring-boot-starter`: spring-web module + providers, autoconfiguration only.

### The store contract

`IdempotencyStoreContract` in `idempotency-test` is the single source of truth for store behavior. Every `IdempotencyStore` implementation must extend it and pass all of it. New store = implement the SPI, extend the contract, implement `store()`. Behavior changes to stores belong in the contract first so every backend is held to them.

## Conventions

- Test naming: `When_<Context>_Expect_<Result>`.
- Formatting is Palantir Java Format via Spotless; license headers are inserted by `spotless:apply`, never by hand.
- Keep Javadoc on public API valid - release builds (`-Prelease`) run doclint and fail on malformed Javadoc.
