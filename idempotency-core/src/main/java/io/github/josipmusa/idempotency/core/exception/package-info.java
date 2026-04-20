/**
 * Exception hierarchy for idempotency failures.
 *
 * <p>All exceptions are unchecked ({@link RuntimeException} subtypes) because
 * idempotency failures are infrastructure errors that callers typically cannot
 * recover from inline — they should propagate to the application's error handler.
 *
 * <ul>
 *   <li>{@link io.github.josipmusa.idempotency.core.exception.IdempotencyException} — base class
 *       for all idempotency-related exceptions.</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.exception.IdempotencyStoreException} — wraps
 *       storage-level failures (database unreachable, serialization errors).</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.exception.IdempotencyLockTimeoutException} —
 *       thrown when a caller's {@code lockTimeout} expires while waiting for an
 *       in-flight request to complete.</li>
 *   <li>{@link io.github.josipmusa.idempotency.core.exception.IdempotencyFingerprintMismatchException} —
 *       thrown when the same idempotency key is reused with a different request body;
 *       the adapter maps this to HTTP 422.</li>
 * </ul>
 */
package io.github.josipmusa.idempotency.core.exception;
