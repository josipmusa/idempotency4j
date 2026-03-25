package io.github.josipmusa.core;

/**
 * The result of {@link IdempotencyStore#tryAcquire} — one of three outcomes
 * that determine what happens next in the idempotency lifecycle.
 *
 * <p>Use pattern matching to handle each case:
 * <pre>{@code
 * switch (store.tryAcquire(context)) {
 *     case AcquireResult.Acquired a   -> // run the action
 *     case AcquireResult.Duplicate d  -> // replay d.response()
 *     case AcquireResult.LockTimeout t -> // timeout, reject request
 * }
 * }</pre>
 */
public sealed interface AcquireResult
        permits AcquireResult.Acquired, AcquireResult.Duplicate, AcquireResult.LockTimeout {

    /**
     * Lock obtained — this caller owns the key and should execute the action.
     * The key is now IN_PROGRESS. The caller must eventually call either
     * {@link IdempotencyStore#complete} or {@link IdempotencyStore#release}.
     */
    record Acquired() implements AcquireResult {}

    /**
     * Key was already completed — contains the stored response for replay.
     * The action must NOT be executed again.
     */
    record Duplicate(StoredResponse response) implements AcquireResult {}

    /**
     * Key is in-flight (held by another caller) and this caller's
     * {@code lockTimeout} expired while waiting. The action was not
     * executed. The caller should return an appropriate error (e.g. 409 or 503).
     */
    record LockTimeout(String key) implements AcquireResult {}

    static AcquireResult acquired() {
        return new Acquired();
    }

    static AcquireResult duplicate(StoredResponse response) {
        return new Duplicate(response);
    }

    static AcquireResult lockTimeout(String key) {
        return new LockTimeout(key);
    }
}
