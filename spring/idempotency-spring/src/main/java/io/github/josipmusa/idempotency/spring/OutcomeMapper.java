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

import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.Outcome;

/**
 * Turns what the engine decided into what the intercepted method returns.
 *
 * <p>The seam for callers whose transport has its own answer for a key already in flight -
 * a listener that wants to nack rather than throw, say, or one that returns a sentinel. The
 * default is {@link #defaults()}.
 */
@FunctionalInterface
public interface OutcomeMapper {

    /**
     * Returns the default mapping: a value for {@link Outcome.Executed} and
     * {@link Outcome.Replayed} alike, and {@link IdempotencyInFlightException} for
     * {@link Outcome.InFlight}.
     *
     * <p>A replay returning the same value as the original execution is the whole point of
     * the library; a {@code void} method replays by simply returning.
     *
     * @return the default mapper
     */
    static OutcomeMapper defaults() {
        return DefaultOutcomeMapper.INSTANCE;
    }

    /**
     * Maps an outcome to the intercepted method's return value.
     *
     * @param outcome what the engine did with the call
     * @param context the context the call ran under, for error messages
     * @return what the intercepted method returns; {@code null} for a {@code void} method
     * @throws IdempotencyInFlightException if the implementation reports an in-flight
     *                                      duplicate as an exception, as the default does
     */
    Object map(Outcome<Object> outcome, IdempotencyContext context);
}
