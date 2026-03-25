package io.github.josipmusa.core;

/**
 * A {@link Runnable} that can throw checked exceptions.
 *
 * <p>Used as the action parameter in {@link IdempotencyEngine#execute}
 * because business logic commonly throws checked exceptions (e.g.
 * {@code IOException}) that should propagate to the caller unchanged,
 * not wrapped in {@code RuntimeException}.
 */
@FunctionalInterface
public interface ThrowingRunnable {
    void run() throws Exception;
}
