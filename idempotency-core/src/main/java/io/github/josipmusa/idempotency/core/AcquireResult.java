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
import java.time.Instant;
import java.util.Objects;

/**
 * The result of {@link IdempotencyStore#tryAcquire} — one of four outcomes
 * that determine what happens next in the idempotency lifecycle.
 *
 * <p>Use pattern matching to handle each case:
 * <pre>{@code
 * switch (store.tryAcquire(context)) {
 *     case AcquireResult.Acquired a   -> // run the action
 *     case AcquireResult.Duplicate d  -> // replay d.payload()
 *     case AcquireResult.InFlight f  -> // still running, reject and retry after f.retryAfter()
 *     case AcquireResult.FingerprintMismatch fm -> // reject: key reused with different body
 * }
 * }</pre>
 */
public sealed interface AcquireResult
        permits AcquireResult.Acquired,
                AcquireResult.Duplicate,
                AcquireResult.InFlight,
                AcquireResult.FingerprintMismatch {

    /**
     * Lock obtained — this caller owns the identity and should execute the action.
     * The record is now IN_PROGRESS. The caller must eventually call either
     * {@link IdempotencyStore#complete} or {@link IdempotencyStore#release}, passing
     * this lease ID so a stale owner cannot mutate a newer acquisition.
     */
    record Acquired(String leaseId) implements AcquireResult {
        public Acquired {
            Objects.requireNonNull(leaseId, "leaseId must not be null");
            if (leaseId.isBlank()) {
                throw new IllegalArgumentException("leaseId must not be blank");
            }
        }
    }

    /**
     * Identity was already completed — carries the stored payload for replay, and when the
     * original operation finished. The action must NOT be executed again.
     *
     * @param payload     what the original execution stored; {@link Payload#none()} when it
     *                    had nothing to replay
     * @param completedAt when the store recorded that completion
     */
    record Duplicate(Payload payload, Instant completedAt) implements AcquireResult {
        public Duplicate {
            Objects.requireNonNull(payload, "payload must not be null");
            Objects.requireNonNull(completedAt, "completedAt must not be null");
        }
    }

    /**
     * Identity is in-flight (held by another caller) and this caller's
     * {@code waitTimeout} elapsed without the holder finishing. The action was not
     * executed. The caller should return an appropriate error (e.g. 409 or 503).
     *
     * <p>{@code retryAfter} is how much of the holder's lease was left when the store
     * gave up — an upper bound on how long the identity can stay in-flight before it
     * becomes stealable. It is never negative; a store that cannot tell reports
     * {@link Duration#ZERO}.
     */
    record InFlight(Duration retryAfter) implements AcquireResult {
        public InFlight {
            Objects.requireNonNull(retryAfter, "retryAfter must not be null");
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative, got: " + retryAfter);
            }
        }
    }

    /**
     * Identity was already completed and both the stored and the incoming request
     * carry a fingerprint, but the two differ. An HTTP adapter should return
     * 422 to indicate the key was reused with a different payload.
     *
     * <p>A missing fingerprint on either side is not a mismatch — see
     * {@link IdempotencyStore#tryAcquire}.
     */
    record FingerprintMismatch(String storedFingerprint, String receivedFingerprint) implements AcquireResult {}

    static AcquireResult acquired(String leaseId) {
        return new Acquired(leaseId);
    }

    static AcquireResult duplicate(Payload payload, Instant completedAt) {
        return new Duplicate(payload, completedAt);
    }

    static AcquireResult inFlight(Duration retryAfter) {
        return new InFlight(retryAfter);
    }

    static AcquireResult fingerprintMismatch(String storedFingerprint, String receivedFingerprint) {
        return new FingerprintMismatch(storedFingerprint, receivedFingerprint);
    }
}
