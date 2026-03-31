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
 *     case AcquireResult.FingerprintMismatch fm -> // reject: key reused with different body
 * }
 * }</pre>
 */
public sealed interface AcquireResult
        permits AcquireResult.Acquired,
                AcquireResult.Duplicate,
                AcquireResult.LockTimeout,
                AcquireResult.FingerprintMismatch {

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

    /**
     * Key was already completed but the request fingerprint does not match
     * the one stored with the original request. The caller should return
     * HTTP 422 to indicate the key was reused with a different payload.
     */
    record FingerprintMismatch(String storedFingerprint, String receivedFingerprint) implements AcquireResult {}

    static AcquireResult acquired() {
        return new Acquired();
    }

    static AcquireResult duplicate(StoredResponse response) {
        return new Duplicate(response);
    }

    static AcquireResult lockTimeout(String key) {
        return new LockTimeout(key);
    }

    static AcquireResult fingerprintMismatch(String storedFingerprint, String receivedFingerprint) {
        return new FingerprintMismatch(storedFingerprint, receivedFingerprint);
    }
}
