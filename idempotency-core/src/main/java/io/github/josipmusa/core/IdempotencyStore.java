package io.github.josipmusa.core;

import java.time.Duration;

/**
 * SPI for idempotency key persistence and in-flight coordination.
 *
 * <p>Implementations handle storage, locking, and blocking. The engine
 * calls {@link #tryAcquire} exactly once per request — the store is
 * responsible for all waiting and lock-stealing internally.
 *
 * <h2>State machine for a key</h2>
 * <pre>
 * [not exists] ──tryAcquire──→ IN_PROGRESS ──complete()──→ COMPLETE
 *                                   │
 *                               release()
 *                                   │
 *                                   ↓
 *                                FAILED ──tryAcquire──→ IN_PROGRESS
 * </pre>
 *
 * <p>COMPLETE keys return {@link AcquireResult.Duplicate} until their TTL
 * expires, after which they are treated as new. IN_PROGRESS keys whose
 * lock has expired are considered stale and may be stolen by a subsequent
 * {@code tryAcquire} caller.
 *
 * <h2>Implementation requirements</h2>
 * <ul>
 *   <li>Every implementation must pass {@code IdempotencyStoreContract}
 *       from the {@code idempotency-test} module.</li>
 *   <li>{@code tryAcquire} must handle all blocking internally — the
 *       engine never polls or retries.</li>
 *   <li>{@code extendLock} must be a silent no-op for unknown or
 *       non-IN_PROGRESS keys (the heartbeat may fire after completion).</li>
 *   <li>{@code complete} and {@code release} must reject calls for keys
 *       that are not IN_PROGRESS.</li>
 * </ul>
 */
public interface IdempotencyStore {

    /**
     * Attempts to acquire the idempotency lock for the given key.
     *
     * <p>This is the only entry point into the state machine. The method
     * blocks internally if the key is IN_PROGRESS (held by another caller)
     * and returns one of three outcomes:
     * <ul>
     *   <li>{@link AcquireResult.Acquired} — lock obtained, caller should
     *       execute the action and then call {@link #complete}.</li>
     *   <li>{@link AcquireResult.Duplicate} — key was already completed,
     *       response is attached for replay.</li>
     *   <li>{@link AcquireResult.LockTimeout} — key is in-flight and the
     *       caller's {@code lockTimeout} expired while waiting.</li>
     * </ul>
     *
     * <p>Stale locks (IN_PROGRESS with expired {@code lockExpiresAt}) are
     * stolen atomically — the caller receives {@code Acquired} as if the
     * key were new. FAILED keys are reclaimed the same way.
     *
     * @param context contains the key, TTL, and lockTimeout for this request
     * @return the acquisition outcome — never null
     * @throws io.github.josipmusa.core.exception.IdempotencyStoreException
     *         if the underlying storage is unreachable
     */
    AcquireResult tryAcquire(IdempotencyContext context);

    /**
     * Transitions an IN_PROGRESS key to COMPLETE with the given response.
     *
     * <p>Called by the adapter (not the engine) after the action has
     * executed and the HTTP response has been captured. The stored
     * response will be returned to subsequent callers via
     * {@link AcquireResult.Duplicate} until {@code ttl} expires.
     *
     * @param key      the idempotency key, must match a prior {@code tryAcquire}
     * @param response the HTTP response to store for duplicate replay
     * @param ttl      how long to keep the completed entry before expiry
     * @throws io.github.josipmusa.core.exception.IdempotencyStoreException
     *         if the key does not exist or is not IN_PROGRESS
     */
    void complete(String key, StoredResponse response, Duration ttl);

    /**
     * Transitions an IN_PROGRESS key to FAILED, allowing it to be retried.
     *
     * <p>Called by the engine when the action throws. The key becomes
     * immediately reclaimable by the next {@code tryAcquire} caller.
     *
     * @param key the idempotency key to release
     * @throws io.github.josipmusa.core.exception.IdempotencyStoreException
     *         if the key does not exist or is not IN_PROGRESS
     */
    void release(String key);

    /**
     * Extends the lock expiration for an IN_PROGRESS key.
     *
     * <p>Called by the engine's heartbeat at {@code lockTimeout / 2}
     * intervals to prevent the lock from being stolen while a
     * long-running action is still executing.
     *
     * <p>Must be a <strong>silent no-op</strong> if the key does not
     * exist or is not IN_PROGRESS. The heartbeat may fire after the
     * key has already been completed or released — this is expected
     * and must not throw.
     *
     * @param key       the idempotency key whose lock to extend
     * @param extension the new lock duration measured from now
     */
    void extendLock(String key, Duration extension);

    /**
     * Purges all expired records from the store.
     *
     * <p>The following records are eligible for purging:
     * <ul>
     *   <li>{@code COMPLETE} records whose {@code expires_at} is in the past</li>
     *   <li>{@code FAILED} records whose {@code expires_at} is in the past</li>
     *   <li>{@code IN_PROGRESS} records whose {@code lock_expires_at}
     *       and {@code expires_at} are both in the past — indicating a
     *       crashed caller whose TTL window has also closed</li>
     * </ul>
     *
     * <p>This method does not schedule itself. Callers are responsible for
     * invoking it periodically. When using the Spring Boot starter, a
     * {@code @Scheduled} task is wired automatically using the cron
     * expression defined by {@code idempotency.purge.cron}
     * (default: hourly). In plain Java usage, schedule this method
     * with a {@link java.util.concurrent.ScheduledExecutorService}.
     *
     * <p>This method is safe to call concurrently — multiple application
     * instances invoking it simultaneously will not cause correctness
     * issues, only redundant work.
     *
     * @return the number of records deleted
     */
    int purgeExpired();
}
