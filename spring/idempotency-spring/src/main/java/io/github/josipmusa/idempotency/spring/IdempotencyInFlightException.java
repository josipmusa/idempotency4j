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
package io.github.josipmusa.idempotency.spring;

import io.github.josipmusa.idempotency.core.Outcome;
import io.github.josipmusa.idempotency.core.exception.IdempotencyException;
import java.io.Serial;
import java.time.Duration;
import java.util.Objects;

/**
 * Thrown when another caller holds the key and did not finish in time, so this call did not
 * run.
 *
 * <p>The core reports that as {@link Outcome.InFlight} rather than an exception, because it
 * is not an error - the work is being done, just not here. A method behind
 * {@link Idempotent} has no outcome to return though: it either produces the value or it does
 * not, so the interceptor turns the outcome into this exception and lets whatever invoked the
 * method decide. For a message listener that means the broker redelivers, which is exactly
 * the right answer - by then the holder has usually finished and the redelivery replays.
 *
 * <p>{@link #retryAfter()} is how much of the holder's lease was left, so it is an upper
 * bound on how long the key can stay in flight before it becomes stealable.
 */
public class IdempotencyInFlightException extends IdempotencyException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    /**
     * @param message    what happened, for the log
     * @param retryAfter how long the caller should wait before trying again; never negative
     */
    public IdempotencyInFlightException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter must not be null");
    }

    /**
     * Returns how long to wait before retrying: the remaining lease of the caller that holds
     * the key.
     *
     * @return the retry delay; never negative
     */
    public Duration retryAfter() {
        return retryAfter;
    }
}
