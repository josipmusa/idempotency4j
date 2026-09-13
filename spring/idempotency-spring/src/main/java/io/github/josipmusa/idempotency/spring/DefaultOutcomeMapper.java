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

/** The mapper behind {@link OutcomeMapper#defaults()}. */
final class DefaultOutcomeMapper implements OutcomeMapper {

    static final DefaultOutcomeMapper INSTANCE = new DefaultOutcomeMapper();

    private DefaultOutcomeMapper() {}

    @Override
    public Object map(Outcome<Object> outcome, IdempotencyContext context) {
        return switch (outcome) {
            case Outcome.Executed<Object>(Object value) -> value;
            case Outcome.Replayed<Object>(Object value, var ignoredCompletedAt) -> value;
            case Outcome.InFlight<Object>(var retryAfter) ->
                throw new IdempotencyInFlightException(
                        "Another caller is already running " + context.identity() + "; retry after " + retryAfter,
                        retryAfter);
        };
    }
}
