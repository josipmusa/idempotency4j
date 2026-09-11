/*
 * Copyright 2026 Josip Musa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.josipmusa.idempotency.core;

import java.time.Duration;

/**
 * SPI for idempotency record persistence and in-flight coordination.
 *
 * <p>Implementations handle storage, locking, and blocking. The engine
 * calls {@link #tryAcquire} exactly once per request — the store is
 * responsible for all waiting and lock-stealing internally.
 *
 * <h2>Identity</h2>
 * <p>A record is identified by an {@link IdempotencyIdentity}: a scope and a key,
 * together. Two acquisitions with the same key under different scopes are two
 * records that never see each other. A store must never dedupe on the key alone.
 *
 * <h2>State machine for a record</h2>
 * <pre>
 * [not exists] ──tryAcquire──→ IN_PROGRESS ──complete(leaseId)──→ COMPLETE
 *                                   │
 *                           release(leaseId)
 *                                   │
 *                                   ↓
 *                                FAILED ──tryAcquire──→ IN_PROGRESS
 * </pre>
 *
 * <p>COMPLETE records return {@link AcquireResult.Duplicate} until their TTL
 * expires, after which they are treated as new. IN_PROGRESS records whose
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
 *       non-IN_PROGRESS records (the heartbeat may fire after completion).</li>
 *   <li>{@code complete} and {@code release} must reject calls for records
 *       that are not IN_PROGRESS or are owned by a different lease.</li>
 * </ul>
 */
public interface IdempotencyStore {

    /**
     * Attempts to acquire the idempotency lock for the context's
     * {@link IdempotencyContext#identity() identity}.
     *
     * <p>This is the only entry point into the state machine. The method
     * blocks internally for up to {@link IdempotencyContext#waitTimeout()} if the
     * record is IN_PROGRESS (held by another caller) and returns one of four
     * outcomes:
     * <ul>
     *   <li>{@link AcquireResult.Acquired} — lock obtained, caller should
     *       execute the action and then call {@link #complete}.</li>
     *   <li>{@link AcquireResult.Duplicate} — the record was already completed,
     *       the stored payload is attached for replay.</li>
     *   <li>{@link AcquireResult.InFlight} — the record is still held by another
     *       caller and this caller's {@code waitTimeout} elapsed. The attached
     *       {@code retryAfter} is the remaining lease of the current holder at the
     *       moment the store gave up, floored at zero.</li>
     *   <li>{@link AcquireResult.FingerprintMismatch} — the record is COMPLETE but
     *       belongs to a different request payload.</li>
     * </ul>
     *
     * <p>Stale acquisitions (IN_PROGRESS with an expired lease) are
     * stolen atomically — the caller receives {@code Acquired} as if the
     * record were new. FAILED records are reclaimed the same way.
     *
     * <p><strong>Fingerprint comparison.</strong>
     * {@link IdempotencyContext#requestFingerprint()} is optional, so a stored
     * record and an incoming acquisition may disagree about whether a fingerprint
     * exists at all. Every implementation must apply the same rule:
     * <ul>
     *   <li>Both present and different — {@link AcquireResult.FingerprintMismatch}.</li>
     *   <li>Both present and equal, or both absent — proceed normally.</li>
     *   <li>One present and the other absent — <strong>not</strong> a mismatch;
     *       proceed normally (duplicate or in-flight, as the state dictates).
     *       A caller that does not fingerprint cannot contradict one that does.</li>
     * </ul>
     *
     * @param context contains the identity, TTL, lease duration, and wait timeout for
     *        this request. A {@link java.time.Duration#ZERO} wait means the store must
     *        not block: it looks once and returns {@code InFlight} if the record is held.
     * @return the acquisition outcome — never null
     * @throws io.github.josipmusa.idempotency.core.exception.IdempotencyStoreUnavailableException
     *         if the underlying storage is unreachable
     * @throws io.github.josipmusa.idempotency.core.exception.IdempotencyCorruptRecordException
     *         if an existing owned record cannot be interpreted safely
     */
    AcquireResult tryAcquire(IdempotencyContext context);

    /**
     * Transitions an IN_PROGRESS record to COMPLETE with the given payload.
     *
     * <p>Called once the action has executed and its result has been
     * captured - normally through {@link IdempotencyEngine#complete}, which
     * adds the lifecycle callbacks around this call. The stored payload
     * will be returned to subsequent callers via
     * {@link AcquireResult.Duplicate} until {@code ttl} expires.
     *
     * <p>Implementations must round-trip every {@link IdempotencyPayload}
     * variant: a {@link StoredResponse} replays as an equal
     * {@code StoredResponse}, and a {@link NoPayload} replays as a
     * {@code NoPayload} carrying the same {@code completedAt}.
     *
     * @param identity the identity acquired by a prior {@code tryAcquire}
     * @param leaseId  the lease returned by that successful {@code tryAcquire}
     * @param payload  what to store for duplicate replay
     * @param ttl      how long to keep the completed entry before expiry
     * @throws io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException
     *         if the record does not exist, is not IN_PROGRESS, or is owned by a different lease
     * @throws io.github.josipmusa.idempotency.core.exception.IdempotencyDurabilityException
     *         if the mutation was accepted but requested durability could not be confirmed
     */
    void complete(IdempotencyIdentity identity, String leaseId, IdempotencyPayload payload, Duration ttl);

    /**
     * Transitions an IN_PROGRESS record to FAILED, allowing it to be retried.
     *
     * <p>Called by the engine when the action throws. The record becomes
     * immediately reclaimable by the next {@code tryAcquire} caller.
     *
     * <p>Implementations must leave the record's {@code expires_at} untouched. A
     * FAILED record is reclaimable straight away, and the TTL it was created with
     * is what stops a purge from removing it out from under a retry.
     *
     * @param identity the identity to release
     * @param leaseId  the lease returned by the successful {@code tryAcquire}
     * @throws io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException
     *         if the record does not exist, is not IN_PROGRESS, or is owned by a different lease
     */
    void release(IdempotencyIdentity identity, String leaseId);

    /**
     * Extends the lock expiration for an IN_PROGRESS record.
     *
     * <p>Called by the engine's heartbeat at {@code leaseDuration / 2}
     * intervals to prevent the lease from being stolen while a
     * long-running action is still executing.
     *
     * <p>Must be a <strong>silent no-op</strong> if the record does not
     * exist, is not IN_PROGRESS, or belongs to a different lease. The heartbeat
     * may fire after the record has already been completed, released, or stolen —
     * this is expected and must not throw.
     *
     * @param identity  the identity whose lock to extend
     * @param leaseId   the lease returned by the successful {@code tryAcquire}
     * @param extension the new lock duration measured from now
     */
    void extendLock(IdempotencyIdentity identity, String leaseId, Duration extension);

    /**
     * Purges all expired records from the store.
     *
     * <p>The following records are eligible for purging:
     * <ul>
     *   <li>{@code COMPLETE} records whose {@code expires_at} is in the past</li>
     *   <li>{@code FAILED} records whose {@code expires_at} is in the past</li>
     *   <li>{@code IN_PROGRESS} records whose lease and {@code expires_at}
     *       have both passed — indicating a crashed caller whose TTL window
     *       has also closed</li>
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
