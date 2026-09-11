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
package io.github.josipmusa.idempotency.inmemory;

import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyIdentity;
import io.github.josipmusa.idempotency.core.IdempotencyPayload;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory implementation of {@link IdempotencyStore}.
 *
 * <p>Suitable for local development, testing, and single-instance
 * deployments only. State is not persisted across restarts and is
 * not shared across multiple application instances.
 *
 *
 * <p><strong>Do not use in a horizontally scaled production environment.
 * Use idempotency-jdbc or idempotency-redis instead.</strong>
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final long DEFAULT_POLL_INTERVAL_MS = 50;

    private enum Status {
        IN_PROGRESS,
        COMPLETE,
        FAILED
    }

    /**
     * {@code lockTimeout} is stored so that FAILED entries can be expired after the original lock
     * duration rather than the full TTL — FAILED keys are immediately re-acquirable and are
     * typically retried within seconds, so holding them for the full TTL would waste memory.
     * {@code lockTimeout} is {@code null} for COMPLETE entries where it is not needed.
     */
    private record Entry(
            Status status,
            IdempotencyPayload payload,
            Instant lockExpiresAt,
            Instant expiresAt,
            Duration lockTimeout,
            String requestFingerprint,
            String leaseId) {}

    private final ConcurrentHashMap<IdempotencyIdentity, Entry> store = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long pollIntervalMs;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC(), DEFAULT_POLL_INTERVAL_MS);
    }

    public InMemoryIdempotencyStore(Clock clock) {
        this(clock, DEFAULT_POLL_INTERVAL_MS);
    }

    public InMemoryIdempotencyStore(Clock clock, long pollIntervalMs) {
        this.clock = Objects.requireNonNull(clock);
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive, got: " + pollIntervalMs);
        }
        this.pollIntervalMs = pollIntervalMs;
    }

    @Override
    public AcquireResult tryAcquire(IdempotencyContext context) {
        long startedAtNanos = System.nanoTime();
        long timeoutNanos = context.lockTimeout().toNanos();
        String leaseId = UUID.randomUUID().toString();
        IdempotencyIdentity identity = context.identity();
        boolean firstAttempt = true;

        while (true) {
            if (!firstAttempt && System.nanoTime() - startedAtNanos >= timeoutNanos) {
                return AcquireResult.lockTimeout(identity);
            }
            firstAttempt = false;
            Instant now = clock.instant();

            // Evict expired COMPLETE entry for this identity so a fresh insert can follow
            store.computeIfPresent(
                    identity,
                    (id, entry) -> entry.status() == Status.COMPLETE
                                    && entry.expiresAt().isBefore(now)
                            ? null
                            : entry);

            Entry newEntry = new Entry(
                    Status.IN_PROGRESS,
                    null,
                    now.plus(context.lockTimeout()),
                    now.plus(context.ttl()),
                    context.lockTimeout(),
                    context.requestFingerprint(),
                    leaseId);

            Entry existing = store.putIfAbsent(identity, newEntry);

            if (existing == null) {
                return AcquireResult.acquired(leaseId);
            }

            if (existing.status() == Status.COMPLETE) {
                if (isMismatch(existing.requestFingerprint(), context.requestFingerprint())) {
                    return AcquireResult.fingerprintMismatch(
                            existing.requestFingerprint(), context.requestFingerprint());
                }
                return AcquireResult.duplicate(existing.payload());
            }

            // FAILED or stale IN_PROGRESS — attempt to claim the lock atomically.
            // A stale lock (lockExpiresAt in the past) is claimable by any caller, regardless of
            // the caller's lockTimeout. This matches JDBC behavior.
            if (existing.status() == Status.FAILED
                    || (existing.lockExpiresAt() != null
                            && existing.lockExpiresAt().isBefore(now))) {
                if (store.replace(identity, existing, newEntry)) {
                    return AcquireResult.acquired(leaseId);
                }
                continue; // lost the race — re-inspect on next iteration
            }

            // Active IN_PROGRESS — wait before retrying
            long remainingNanos = timeoutNanos - (System.nanoTime() - startedAtNanos);
            if (remainingNanos <= 0) {
                return AcquireResult.lockTimeout(identity);
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(pollIntervalMs)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AcquireResult.lockTimeout(identity);
            }
        }
    }

    @Override
    public void complete(IdempotencyIdentity identity, String leaseId, IdempotencyPayload payload, Duration ttl) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        store.compute(identity, (id, existing) -> {
            if (existing == null) {
                throw new IdempotencyLeaseLostException(
                        "Cannot complete " + identity + ": no entry exists or it expired");
            }
            if (existing.status() != Status.IN_PROGRESS) {
                throw new IdempotencyLeaseLostException(
                        "Cannot complete " + identity + ": entry is " + existing.status() + ", expected IN_PROGRESS");
            }
            requireLease(existing, leaseId, identity, "complete");
            return new Entry(
                    Status.COMPLETE,
                    payload,
                    null,
                    clock.instant().plus(ttl),
                    null,
                    existing.requestFingerprint(),
                    null);
        });
    }

    @Override
    public void release(IdempotencyIdentity identity, String leaseId) {
        Objects.requireNonNull(identity, "identity must not be null");
        store.compute(identity, (id, existing) -> {
            if (existing == null) {
                throw new IdempotencyLeaseLostException(
                        "Cannot release " + identity + ": no entry exists or it expired");
            }
            if (existing.status() != Status.IN_PROGRESS) {
                throw new IdempotencyLeaseLostException(
                        "Cannot release " + identity + ": entry is " + existing.status() + ", expected IN_PROGRESS");
            }
            requireLease(existing, leaseId, identity, "release");
            // Expire after lockTimeout rather than full TTL — FAILED entries are immediately
            // re-acquirable, so keeping them for the full TTL would unnecessarily retain memory.
            Instant failedExpiry = clock.instant().plus(existing.lockTimeout());
            return new Entry(
                    Status.FAILED,
                    null,
                    null,
                    failedExpiry,
                    existing.lockTimeout(),
                    existing.requestFingerprint(),
                    null);
        });
    }

    @Override
    public void extendLock(IdempotencyIdentity identity, String leaseId, Duration extension) {
        Objects.requireNonNull(identity, "identity must not be null");
        store.computeIfPresent(identity, (id, entry) -> {
            if (entry.status() != Status.IN_PROGRESS || !Objects.equals(entry.leaseId(), leaseId)) {
                return entry;
            }
            return new Entry(
                    Status.IN_PROGRESS,
                    null,
                    clock.instant().plus(extension),
                    entry.expiresAt(),
                    entry.lockTimeout(),
                    entry.requestFingerprint(),
                    entry.leaseId());
        });
    }

    /**
     * A fingerprint present on only one side is not a mismatch — a caller that does not
     * fingerprint its payload cannot contradict one that does. See
     * {@link IdempotencyStore#tryAcquire}.
     */
    private static boolean isMismatch(String storedFingerprint, String incomingFingerprint) {
        return storedFingerprint != null
                && incomingFingerprint != null
                && !storedFingerprint.equals(incomingFingerprint);
    }

    private static void requireLease(Entry entry, String leaseId, IdempotencyIdentity identity, String operation) {
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        if (!leaseId.equals(entry.leaseId())) {
            throw new IdempotencyLeaseLostException(
                    "Cannot " + operation + " " + identity + ": lease no longer owns the record");
        }
    }

    /**
     * Purges all expired entries from the in-memory store.
     *
     * <p>An entry is eligible for purging based on its status:
     * <ul>
     *   <li>{@code COMPLETE} and {@code FAILED} — removed when
     *       {@code expiresAt} is in the past</li>
     *   <li>{@code IN_PROGRESS} — removed only when <em>both</em>
     *       {@code lockExpiresAt} and {@code expiresAt} are in the past.
     *       Entries whose lock has expired but whose TTL has not are
     *       intentionally kept — they remain eligible for lock stealing
     *       by the next {@link #tryAcquire} caller.</li>
     * </ul>
     *
     * <p>This method does not self-schedule. In Spring Boot applications,
     * the starter drives the purge via {@code @Scheduled}. In standalone
     * usage, call this method periodically using a
     * {@link java.util.concurrent.ScheduledExecutorService}:
     *
     * <pre>{@code
     * ScheduledExecutorService scheduler =
     *     Executors.newSingleThreadScheduledExecutor();
     * scheduler.scheduleAtFixedRate(
     *     store::purgeExpired, 5000, 5000, TimeUnit.MILLISECONDS);
     * }</pre>
     *
     * @return the number of entries removed
     */
    @Override
    public int purgeExpired() {
        Instant now = clock.instant();
        AtomicInteger count = new AtomicInteger(0);
        store.entrySet().removeIf(e -> {
            if (isExpired(e.getValue(), now)) {
                count.incrementAndGet();
                return true;
            }
            return false;
        });
        return count.get();
    }

    private static boolean isExpired(Entry entry, Instant now) {
        return switch (entry.status()) {
            case COMPLETE, FAILED ->
                entry.expiresAt() != null && entry.expiresAt().isBefore(now);
            case IN_PROGRESS ->
                entry.lockExpiresAt() != null
                        && entry.lockExpiresAt().isBefore(now)
                        && entry.expiresAt() != null
                        && entry.expiresAt().isBefore(now);
        };
    }
}
