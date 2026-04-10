/**
 * In-memory {@link io.github.josipmusa.core.IdempotencyStore} implementation.
 *
 * <p>{@link io.github.josipmusa.idempotency.inmemory.InMemoryIdempotencyStore} is
 * suitable for single-instance deployments and local development. It is not suitable
 * for horizontally scaled environments — lock coordination and storage are
 * per-JVM and are lost on restart.
 *
 * <p>This implementation is also the recommended test double when writing tests that
 * need an {@code IdempotencyStore} without external infrastructure.
 */
package io.github.josipmusa.idempotency.inmemory;
