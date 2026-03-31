package io.github.josipmusa.idempotency.inmemory;

import io.github.josipmusa.core.AcquireResult;
import io.github.josipmusa.core.IdempotencyContext;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.core.StoredResponse;
import io.github.josipmusa.core.exception.IdempotencyStoreException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
            StoredResponse response,
            Instant lockExpiresAt,
            Instant expiresAt,
            Duration lockTimeout,
            String requestFingerprint) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
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
        Instant deadline = clock.instant().plus(context.lockTimeout());

        while (true) {
            Instant now = clock.instant();
            if (now.isAfter(deadline)) {
                return AcquireResult.lockTimeout(context.key());
            }

            // Evict expired COMPLETE entry for this key so a fresh insert can follow
            store.computeIfPresent(
                    context.key(),
                    (key, entry) -> entry.status() == Status.COMPLETE
                                    && entry.expiresAt().isBefore(now)
                            ? null
                            : entry);

            Entry newEntry = new Entry(
                    Status.IN_PROGRESS,
                    null,
                    now.plus(context.lockTimeout()),
                    now.plus(context.ttl()),
                    context.lockTimeout(),
                    context.requestFingerprint());

            Entry existing = store.putIfAbsent(context.key(), newEntry);

            if (existing == null) {
                return AcquireResult.acquired();
            }

            if (existing.status() == Status.COMPLETE) {
                if (!existing.requestFingerprint().equals(context.requestFingerprint())) {
                    return AcquireResult.fingerprintMismatch(
                            existing.requestFingerprint(), context.requestFingerprint());
                }
                return AcquireResult.duplicate(existing.response());
            }

            // FAILED or stale IN_PROGRESS — attempt to claim the lock atomically.
            // A stale lock is only stealable when the caller is willing to hold the lock
            // longer than the original holder (context.lockTimeout > existing.lockTimeout).
            // This prevents a short-lived caller from stealing a lock that was held briefly,
            // ensuring such callers get LockTimeout instead.
            if (existing.status() == Status.FAILED
                    || (existing.lockExpiresAt() != null
                            && existing.lockExpiresAt().isBefore(now)
                            && existing.lockTimeout() != null
                            && context.lockTimeout().compareTo(existing.lockTimeout()) > 0)) {
                if (store.replace(context.key(), existing, newEntry)) {
                    return AcquireResult.acquired();
                }
                continue; // lost the race — re-inspect on next iteration
            }

            // Active IN_PROGRESS — wait before retrying
            if (clock.instant().isAfter(deadline)) {
                return AcquireResult.lockTimeout(context.key());
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AcquireResult.lockTimeout(context.key());
            }
        }
    }

    @Override
    public void complete(String key, StoredResponse response, Duration ttl) {
        store.compute(key, (k, existing) -> {
            if (existing == null) {
                throw new IdempotencyStoreException(
                        "Cannot complete key '" + key + "': no entry exists. Was tryAcquire called?");
            }
            if (existing.status() != Status.IN_PROGRESS) {
                throw new IdempotencyStoreException(
                        "Cannot complete key '" + key + "': entry is " + existing.status() + ", expected IN_PROGRESS");
            }
            return new Entry(
                    Status.COMPLETE, response, null, clock.instant().plus(ttl), null, existing.requestFingerprint());
        });
    }

    @Override
    public void release(String key) {
        store.compute(key, (k, existing) -> {
            if (existing == null) {
                throw new IdempotencyStoreException(
                        "Cannot release key '" + key + "': no entry exists. Was tryAcquire called?");
            }
            if (existing.status() != Status.IN_PROGRESS) {
                throw new IdempotencyStoreException(
                        "Cannot release key '" + key + "': entry is " + existing.status() + ", expected IN_PROGRESS");
            }
            // Expire after lockTimeout rather than full TTL — FAILED entries are immediately
            // re-acquirable, so keeping them for the full TTL would unnecessarily retain memory.
            Instant failedExpiry = clock.instant().plus(existing.lockTimeout());
            return new Entry(
                    Status.FAILED, null, null, failedExpiry, existing.lockTimeout(), existing.requestFingerprint());
        });
    }

    @Override
    public void extendLock(String key, Duration extension) {
        store.computeIfPresent(key, (k, entry) -> {
            if (entry.status() != Status.IN_PROGRESS) {
                return entry;
            }
            return new Entry(
                    Status.IN_PROGRESS,
                    null,
                    clock.instant().plus(extension),
                    entry.expiresAt(),
                    entry.lockTimeout(),
                    entry.requestFingerprint());
        });
    }

    /**
     * Purges all expired entries from the in-memory store.
     *
     * <p>An entry is eligible for purging based on its status:
     * <ul>
     *   <li>{@code COMPLETE} and {@code FAILED} — removed when
     *       {@code expiresAt} is in the past</li>
     *   <li>{@code IN_PROGRESS} — removed when {@code lockExpiresAt}
     *       is in the past, indicating the previous holder crashed or
     *       timed out. The next {@link #tryAcquire} caller will insert
     *       a fresh entry rather than stealing — the outcome is
     *       identical.</li>
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
            case COMPLETE, FAILED -> entry.expiresAt() != null
                    && entry.expiresAt().isBefore(now);
            case IN_PROGRESS -> entry.lockExpiresAt() != null
                    && entry.lockExpiresAt().isBefore(now)
                    && entry.expiresAt() != null
                    && entry.expiresAt().isBefore(now);
        };
    }
}
