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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An in-memory implementation of {@link IdempotencyStore}.
 *
 * <p>Suitable for local development, testing, and single-instance
 * deployments only. State is not persisted across restarts and is
 * not shared across multiple application instances.
 *
 * <p>A background reaper thread periodically removes expired entries
 * to prevent unbounded memory growth. Call {@link #close()} to shut
 * down the reaper when the store is no longer needed.
 *
 * <p><strong>Do not use in a horizontally scaled production environment.
 * Use idempotency-jdbc or idempotency-redis instead.</strong>
 */
public class InMemoryIdempotencyStore implements IdempotencyStore, AutoCloseable {

    private static final Duration DEFAULT_REAPER_INTERVAL = Duration.ofMinutes(1);
    private static final long DEFAULT_POLL_INTERVAL_MS = 50;

    private record Entry(Status status, StoredResponse response, Instant lockExpiresAt, Instant expiresAt) {}

    private enum Status {
        IN_PROGRESS,
        COMPLETE,
        FAILED
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ScheduledExecutorService reaper;
    private final long pollIntervalMs;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC(), DEFAULT_REAPER_INTERVAL, DEFAULT_POLL_INTERVAL_MS);
    }

    public InMemoryIdempotencyStore(Clock clock) {
        this(clock, DEFAULT_REAPER_INTERVAL, DEFAULT_POLL_INTERVAL_MS);
    }

    public InMemoryIdempotencyStore(Clock clock, Duration reaperInterval) {
        this(clock, reaperInterval, DEFAULT_POLL_INTERVAL_MS);
    }

    public InMemoryIdempotencyStore(Clock clock, Duration reaperInterval, long pollIntervalMs) {
        Objects.requireNonNull(reaperInterval, "reaper interval must not be null");
        this.clock = Objects.requireNonNull(clock);
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive, got: " + pollIntervalMs);
        }
        this.pollIntervalMs = pollIntervalMs;
        this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotency-store-reaper");
            t.setDaemon(true);
            return t;
        });
        long intervalMs = reaperInterval.toMillis();
        reaper.scheduleAtFixedRate(this::evictExpired, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public AcquireResult tryAcquire(IdempotencyContext context) {
        Instant deadline = clock.instant().plus(context.lockTimeout());

        while (true) {
            // Remove expired COMPLETED entries
            store.computeIfPresent(context.key(), (key, entry) -> {
                if (entry.status() == Status.COMPLETE
                        && entry.expiresAt() != null
                        && entry.expiresAt().isBefore(clock.instant())) {
                    return null;
                }
                return entry;
            });

            // Attempt atomic insert
            Entry newEntry = new Entry(
                    Status.IN_PROGRESS,
                    null,
                    clock.instant().plus(context.lockTimeout()),
                    clock.instant().plus(context.ttl()));

            Entry existing = store.putIfAbsent(context.key(), newEntry);

            if (existing == null) {
                return AcquireResult.acquired();
            }

            // Key exists — check its state
            if (existing.status() == Status.COMPLETE) {
                return AcquireResult.duplicate(existing.response());
            }

            if (existing.status() == Status.FAILED) {
                // Failed entry — attempt to claim
                if (store.replace(context.key(), existing, newEntry)) {
                    return AcquireResult.acquired();
                }
                continue;
            }

            if (existing.lockExpiresAt() != null && existing.lockExpiresAt().isBefore(clock.instant())) {
                // Stale lock — attempt to steal
                if (store.replace(context.key(), existing, newEntry)) {
                    return AcquireResult.acquired();
                }
                continue;
            }

            // IN_PROGRESS with valid lock — wait
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
            return new Entry(Status.COMPLETE, response, null, clock.instant().plus(ttl));
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
            return new Entry(Status.FAILED, null, null, existing.expiresAt());
        });
    }

    @Override
    public void extendLock(String key, Duration extension) {
        store.computeIfPresent(key, (k, entry) -> {
            if (entry.status() != Status.IN_PROGRESS) {
                return entry;
            }
            return new Entry(Status.IN_PROGRESS, null, clock.instant().plus(extension), entry.expiresAt());
        });
    }

    @Override
    public void close() {
        reaper.shutdownNow();
    }

    private void evictExpired() {
        Instant now = clock.instant();
        store.entrySet().removeIf(e -> {
            Entry entry = e.getValue();
            if (entry.status() == Status.COMPLETE
                    && entry.expiresAt() != null
                    && entry.expiresAt().isBefore(now)) {
                return true;
            }
            if (entry.status() == Status.IN_PROGRESS
                    && entry.lockExpiresAt() != null
                    && entry.lockExpiresAt().isBefore(now)
                    && entry.expiresAt() != null
                    && entry.expiresAt().isBefore(now)) {
                return true;
            }
            return entry.status() == Status.FAILED
                    && entry.expiresAt() != null
                    && entry.expiresAt().isBefore(now);
        });
    }
}
