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
 * What {@link IdempotencyEngine#execute} did with the call - the engine-to-adapter
 * communication type.
 *
 * <p>The engine owns the whole lifecycle, so by the time an outcome exists there is
 * nothing left for the adapter to record: it only has to turn one of these three cases
 * into whatever its transport says. A fingerprint mismatch is not an outcome - the engine
 * throws {@link io.github.josipmusa.idempotency.core.exception.IdempotencyFingerprintMismatchException}
 * for it, because a caller that reused a key with a different body asked for something
 * the library cannot answer.
 *
 * <pre>{@code
 * switch (engine.execute(context, action, codec)) {
 *     case Outcome.Executed(var value)        -> // the action ran; value is fresh
 *     case Outcome.Replayed(var value, var at) -> // a duplicate; value came from the store
 *     case Outcome.InFlight(var retryAfter)   -> // someone else holds the key; tell the caller to retry
 * }
 * }</pre>
 *
 * @param <T> what the guarded action produces
 */
// S2326: T is not referenced in this interface's own body, but it is the whole point of the
// type - it flows through the permitted records and is what lets a caller switch on an
// Outcome<StoredResponse> and get a typed value out. Removing it would erase the API.
@SuppressWarnings("java:S2326")
public sealed interface Outcome<T> permits Outcome.Executed, Outcome.Replayed, Outcome.InFlight {

    /**
     * The action ran and the store recorded the completion.
     *
     * <p>With {@link CompletionFailurePolicy#LOG_AND_RETURN} this is also what the engine
     * returns when the action ran but the completion could not be recorded; the failure is
     * logged and reported to {@link IdempotencyLifecycleListener#onFailed}.
     *
     * @param value what the action returned; {@code null} when it produces nothing
     * @param <T>   what the guarded action produces
     */
    record Executed<T>(T value) implements Outcome<T> {}

    /**
     * The action did not run - a previous execution already completed this key, and
     * {@code value} is that execution's result decoded from the store.
     *
     * @param value       the stored result, as the codec decoded it
     * @param completedAt when the store recorded the original completion
     * @param <T>         what the guarded action produces
     */
    record Replayed<T>(T value, Instant completedAt) implements Outcome<T> {
        public Replayed {
            Objects.requireNonNull(completedAt, "completedAt must not be null");
        }
    }

    /**
     * The action did not run - another caller holds the key and did not finish within this
     * context's {@code waitTimeout}.
     *
     * @param retryAfter how much of the holder's lease was left when the store gave up: an
     *                   upper bound on how long the key can stay in-flight before it becomes
     *                   stealable. Never negative
     * @param <T>        what the guarded action produces
     */
    record InFlight<T>(Duration retryAfter) implements Outcome<T> {
        public InFlight {
            Objects.requireNonNull(retryAfter, "retryAfter must not be null");
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative, got: " + retryAfter);
            }
        }
    }
}
