/**
 * Core idempotency engine and SPI interfaces.
 *
 * <p>This package contains the framework-agnostic heart of the library:
 *
 * <ul>
 *   <li>{@link io.github.josipmusa.core.IdempotencyEngine} — orchestrates the
 *       lock lifecycle and heartbeat; knows nothing about HTTP or databases.</li>
 *   <li>{@link io.github.josipmusa.core.IdempotencyStore} — SPI for persistence
 *       and in-flight coordination; implement this to add a new backend.</li>
 *   <li>{@link io.github.josipmusa.core.IdempotencyContext} — fully resolved
 *       request parameters passed to the engine; no nulls, no optionals.</li>
 *   <li>{@link io.github.josipmusa.core.IdempotencyConfig} — configuration
 *       defaults used by adapters to build an {@code IdempotencyContext}.</li>
 *   <li>{@link io.github.josipmusa.core.AcquireResult} — sealed outcome of
 *       {@code IdempotencyStore.tryAcquire}: {@code Acquired}, {@code Duplicate},
 *       or {@code LockTimeout}.</li>
 *   <li>{@link io.github.josipmusa.core.ExecutionResult} — sealed outcome of
 *       {@code IdempotencyEngine.execute}: {@code Executed} or {@code Duplicate}.</li>
 *   <li>{@link io.github.josipmusa.core.StoredResponse} — captured HTTP response
 *       replayed to duplicate callers.</li>
 *   <li>{@link io.github.josipmusa.core.ResponseSanitizer} — SPI for scrubbing
 *       sensitive data from responses before they are persisted.</li>
 * </ul>
 *
 * <p>This package has zero framework dependencies. Adding Spring, JDBC, or Redis
 * imports here is a design error.
 */
package io.github.josipmusa.core;
