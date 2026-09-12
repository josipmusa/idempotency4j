/**
 * Core idempotency engine and SPI interfaces.
 *
 * <p>This package contains the transport- and framework-agnostic heart of the
 * library. Nothing here knows about HTTP, so a message listener or an event
 * handler can drive it exactly as the Servlet adapter does:
 *
 * <ul>
 *   <li>{@link io.github.josipmusa.idempotency.core.IdempotencyEngine} — orchestrates the
 *       lock lifecycle and heartbeat; knows nothing about HTTP or databases.</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.IdempotencyStore} — SPI for persistence
 *       and in-flight coordination; implement this to add a new backend.</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.IdempotencyContext} — fully resolved
 *       parameters passed to the engine; only the request fingerprint is optional.</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.IdempotencyConfig} — configuration
 *       defaults used by adapters to build an {@code IdempotencyContext}.</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.AcquireResult} — sealed outcome of
 *       {@code IdempotencyStore.tryAcquire}: {@code Acquired}, {@code Duplicate},
 *       {@code InFlight}, or {@code FingerprintMismatch}.</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.Outcome} — sealed outcome of
 *       {@code IdempotencyEngine.execute}: {@code Executed}, {@code Replayed}, or
 *       {@code InFlight}.</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.CompletionFailurePolicy} — whether a
 *       completion the store refused is rethrown or logged and swallowed.</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener} - observes
 *       acquisition, completion, failure, duplicate detection, and in-flight rejection on
 *       the calling thread.</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.Payload} — the transport-neutral
 *       envelope a completed operation left behind: a type, a body, and flat string
 *       attributes, stored verbatim and handed back on a duplicate.
 *       {@link io.github.josipmusa.idempotency.core.Payload#none()} is what a caller with
 *       nothing to replay stores.</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.PayloadCodec} — translates between an
 *       adapter's own result type and that envelope.</li>
 * </ul>
 *
 * <p>This package has zero framework dependencies. Adding Spring, JDBC, or Redis
 * imports here is a design error.
 */
package io.github.josipmusa.idempotency.core;
