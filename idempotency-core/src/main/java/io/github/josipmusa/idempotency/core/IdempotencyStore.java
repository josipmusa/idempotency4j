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
 * [absent] ──tryAcquire──→ IN_PROGRESS ──complete(leaseId)──→ COMPLETE
 *                               │
 *                       release(leaseId)
 *                               │
 *                               ↓
 *                            [absent]
 * </pre>
 *
 * <p>There are two states, not three: a record is absent, IN_PROGRESS, or COMPLETE.
 * A failed attempt leaves no trace — {@code release} deletes the record, so the next
 * {@code tryAcquire} sees a key that was never used.
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
 *       that are absent, not IN_PROGRESS, or owned by a different lease, by
 *       throwing {@code IdempotencyLeaseLostException}.</li>
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
     *       the stored payload and its completion instant are attached for replay.</li>
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
     * record were new.
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
     * <p>Implementations must round-trip a {@link Payload} whole: a duplicate caller
     * gets back an equal payload - same {@code type}, same {@code body} bytes, same
     * {@code attributes} - including for {@link Payload#none()}. The store also records
     * when it made the transition and reports that back as
     * {@link AcquireResult.Duplicate#completedAt()}; completion time is the store's to
     * determine, not the caller's.
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
    void complete(IdempotencyIdentity identity, String leaseId, Payload payload, Duration ttl);

    /**
     * Deletes an IN_PROGRESS record, allowing the key to be used again.
     *
     * <p>Called by the engine when the action throws. A failed attempt leaves no
     * trace: the record is gone, and the next {@code tryAcquire} for that identity
     * acquires it as if the key were new.
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
     * Reports whether {@link #complete} can run inside a transaction the caller already
     * opened, so the record and the caller's own writes commit together.
     *
     * <p>A store returns {@code true} only when it can be handed the caller's transactional
     * resource - a JDBC store with a transaction-aware connection resolver can; an in-memory
     * map and Redis cannot, and never will. Defaults to {@code false}, so a store that says
     * nothing is assumed not to support it.
     *
     * <p>When this is {@code false}, an {@link IdempotencyEngine} configured with a real
     * {@link TransactionParticipation} is rejected at construction rather than failing on the
     * first request.
     *
     * @return {@code true} if {@link CompletionMode#JOIN_TRANSACTION} is supported
     */
    default boolean supportsTransactionalCompletion() {
        return false;
    }

    /**
     * Purges all expired records from the store.
     *
     * <p>A record is eligible for purging when its {@code expires_at} is in the
     * past <strong>and</strong> nobody owns it: an IN_PROGRESS record is only
     * purgeable once its lease has also expired.
     *
     * <p>The two timestamps answer different questions. {@code expires_at} says how
     * long a completed record stays replayable; the lease says whether a caller
     * still owns the key. Purge collects garbage, so it must ask both. Deleting a
     * record whose lease is still being extended would let a second caller acquire
     * the same key and run the protected action again, which is precisely what this
     * library exists to prevent. An expired lease on its own is the other half of
     * the rule: that record is stealable by the next {@code tryAcquire} caller, not
     * garbage, and it stays until its own {@code expires_at} passes.
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
