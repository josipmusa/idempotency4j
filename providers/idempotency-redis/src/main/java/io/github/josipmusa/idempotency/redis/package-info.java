/**
 * Redis {@link io.github.josipmusa.idempotency.core.IdempotencyStore} implementation.
 *
 * <p>{@link io.github.josipmusa.idempotency.redis.RedisIdempotencyStore} stores each
 * idempotency record as a Redis hash and coordinates locking with Lua scripts, so every
 * state transition is atomic. It depends only on Lettuce — no Spring, no Spring Data Redis.
 *
 * <p>There is no schema to initialize. The connection must be opened with
 * {@link io.github.josipmusa.idempotency.redis.RedisIdempotencyStore#CODEC} so that binary
 * response bodies are stored as raw bytes, and its lifecycle stays with the caller.
 *
 * <p>Expiry is driven by timestamps stored on the record, with a native Redis TTL trailing
 * behind them so memory is reclaimed even when
 * {@link io.github.josipmusa.idempotency.core.IdempotencyStore#purgeExpired()} is never
 * scheduled.
 *
 * <p>All lock coordination (blocking, lock stealing) happens inside the store. The engine
 * never polls or waits externally.
 *
 * <p>Standalone and Sentinel topologies are supported; Redis Cluster is not.
 */
package io.github.josipmusa.idempotency.redis;
