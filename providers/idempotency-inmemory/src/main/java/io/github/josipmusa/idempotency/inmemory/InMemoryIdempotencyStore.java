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
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.Payload;
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
        COMPLETE
    }

    /**
     * {@code leaseExpiresAt} is when this acquisition stops being protected: once it passes,
     * any caller may steal the entry. It is {@code null} for entries nobody holds.
     */
    private record Entry(
            Status status,
            Payload payload,
            Instant completedAt,
            Instant leaseExpiresAt,
            Instant expiresAt,
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
        long waitNanos = context.waitTimeout().toNanos();
        String leaseId = UUID.randomUUID().toString();
        IdempotencyIdentity identity = context.identity();
        boolean firstAttempt = true;

        while (true) {
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
                    null,
                    now.plus(context.leaseDuration()),
                    now.plus(context.ttl()),
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
                return AcquireResult.duplicate(existing.payload(), existing.completedAt());
            }

            // Expired lease — attempt to claim the entry atomically. An expired lease is
            // claimable by any caller, regardless of that caller's own leaseDuration or
            // waitTimeout. This matches JDBC behavior.
            if (existing.leaseExpiresAt() != null && existing.leaseExpiresAt().isBefore(now)) {
                if (store.replace(identity, existing, newEntry)) {
                    return AcquireResult.acquired(leaseId);
                }
                continue; // lost the race — re-inspect on next iteration
            }

            // Active IN_PROGRESS — give up if the caller has no wait budget left.
            // A zero wait never sleeps: the first look is also the last.
            long remainingWaitNanos = firstAttempt ? waitNanos : waitNanos - (System.nanoTime() - startedAtNanos);
            firstAttempt = false;
            if (remainingWaitNanos <= 0) {
                return AcquireResult.inFlight(remainingLease(existing, now));
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remainingWaitNanos, TimeUnit.MILLISECONDS.toNanos(pollIntervalMs)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AcquireResult.inFlight(remainingLease(existing, clock.instant()));
            }
        }
    }

    /** How much of the holder's lease is left, floored at zero. */
    private static Duration remainingLease(Entry holder, Instant now) {
        if (holder.leaseExpiresAt() == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(now, holder.leaseExpiresAt());
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    @Override
    public void complete(IdempotencyIdentity identity, String leaseId, Payload payload, Duration ttl) {
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
            Instant now = clock.instant();
            return new Entry(Status.COMPLETE, payload, now, null, now.plus(ttl), existing.requestFingerprint(), null);
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
            // Removing the entry leaves no trace of the failed attempt: the key is free
            // again and the next tryAcquire sees it as new.
            return null;
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
                    null,
                    clock.instant().plus(extension),
                    entry.expiresAt(),
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
     * <p>An entry is eligible for purging when its {@code expiresAt} is in the past
     * and nobody owns it: an IN_PROGRESS entry also needs an expired lease. A live
     * lease protects the entry however old it is, so a purge can never delete an
     * acquisition that is still running. An expired lease on its own keeps the entry
     * too: it is stealable by the next {@link #tryAcquire} caller, not garbage.
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
        if (entry.expiresAt() == null || !entry.expiresAt().isBefore(now)) {
            return false;
        }
        return entry.status() != Status.IN_PROGRESS
                || (entry.leaseExpiresAt() != null && entry.leaseExpiresAt().isBefore(now));
    }
}
