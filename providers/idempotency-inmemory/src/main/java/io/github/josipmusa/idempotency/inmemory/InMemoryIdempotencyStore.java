package io.github.josipmusa.idempotency.inmemory;

import io.github.josipmusa.core.AcquireResult;
import io.github.josipmusa.core.IdempotencyContext;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.core.StoredResponse;
import io.github.josipmusa.core.exception.IdempotencyStoreException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory implementation of {@link IdempotencyStore}.
 *
 * <p>Suitable for local development, testing, and single-instance
 * deployments only. State is not persisted across restarts and is
 * not shared across multiple application instances.
 *
 * <p><strong>Do not use in a horizontally scaled production environment.
 * Use idempotency-jdbc or idempotency-redis instead.</strong>
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(Status status, StoredResponse response, Instant lockExpiresAt, Instant expiresAt) {}

    private enum Status {
        IN_PROGRESS,
        COMPLETE,
        FAILED
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC());
    }

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public AcquireResult tryAcquire(IdempotencyContext context) {
        Instant deadline = clock.instant().plus(context.lockTimeout());

        while (true) {
            // Remove expired entries
            store.computeIfPresent(context.key(), (key, entry) -> {
                if (entry.expiresAt() != null && entry.expiresAt().isBefore(clock.instant())) {
                    return null;
                }
                return entry;
            });

            // Attempt atomic insert
            Entry newEntry = new Entry(Status.IN_PROGRESS, null, clock.instant().plus(context.lockTimeout()), null);

            Entry existing = store.putIfAbsent(context.key(), newEntry);

            if (existing == null) {
                return AcquireResult.acquired();
            }

            // Key exists — check its state
            if (existing.lockExpiresAt() != null && existing.lockExpiresAt().isBefore(clock.instant())) {
                // Stale lock — attempt to steal
                if (store.replace(context.key(), existing, newEntry)) {
                    return AcquireResult.acquired();
                }
                continue;
            }

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

            // IN_PROGRESS with valid lock — wait
            if (clock.instant().isAfter(deadline)) {
                return AcquireResult.lockTimeout(context.key());
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AcquireResult.lockTimeout(context.key());
            }
        }
    }

    @Override
    public void complete(String key, StoredResponse response, Duration ttl) {
        Entry existing = store.get(key);
        if (existing == null) {
            throw new IdempotencyStoreException(
                    "Cannot complete key '" + key + "': no entry exists. Was tryAcquire called?", null);
        }

        Entry completed =
                new Entry(Status.COMPLETE, response, null, clock.instant().plus(ttl));
        store.put(key, completed);
    }

    @Override
    public void release(String key) {
        Entry existing = store.get(key);
        if (existing == null) {
            throw new IdempotencyStoreException(
                    "Cannot release key '" + key + "': no entry exists. Was tryAcquire called?", null);
        }

        Entry failed = new Entry(Status.FAILED, null, null, null);
        store.put(key, failed);
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
}
