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
package io.github.josipmusa.idempotency.spring.web;

import java.time.Duration;
import java.util.Objects;

/**
 * A request {@link IdempotencyFilter} answers itself, without running the handler.
 *
 * <p>Passed to an {@link IdempotencyRejectionHandler}, which writes the response. The type is
 * sealed, so a handler can cover every case with an exhaustive {@code switch} and the compiler
 * points out a case it misses. Adding a permitted type breaks such a {@code switch}, so a new
 * case is a compatibility-sensitive change.
 */
public sealed interface IdempotencyRejection {

    /** The key header is missing or blank while {@link WebIdempotencyConfig#required()} is true. */
    record MissingKey() implements IdempotencyRejection {}

    /**
     * The key is longer than the store accepts.
     *
     * @param maxLength the longest key accepted
     */
    record KeyTooLong(int maxLength) implements IdempotencyRejection {}

    /**
     * The request body is larger than the filter buffers.
     *
     * @param maxBytes the largest body accepted
     */
    record BodyTooLarge(long maxBytes) implements IdempotencyRejection {}

    /** The key was already used with a different request body. */
    record FingerprintMismatch() implements IdempotencyRejection {}

    /**
     * Another caller still holds the key after this request's wait timeout.
     *
     * <p>The filter has already set {@code Retry-After} from {@code retryAfter} when the handler
     * runs, so a handler that only changes the body keeps it; a handler may still replace it.
     *
     * @param retryAfter      how long the client should wait before retrying
     * @param suggestedStatus the configured {@link WebIdempotencyConfig#inFlightStatus()}; a
     *                        handler that keeps it stays consistent with the configuration, and
     *                        may still choose another status
     */
    record InFlight(Duration retryAfter, int suggestedStatus) implements IdempotencyRejection {
        public InFlight {
            Objects.requireNonNull(retryAfter, "retryAfter must not be null");
        }
    }
}
