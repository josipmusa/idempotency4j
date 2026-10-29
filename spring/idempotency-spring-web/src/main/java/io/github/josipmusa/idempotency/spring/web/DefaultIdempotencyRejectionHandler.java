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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * The rejection responses {@link IdempotencyFilter} writes when the application declares no
 * {@link IdempotencyRejectionHandler}: a JSON {@code {"error": "..."}} body with these statuses.
 *
 * <ul>
 *   <li>{@link IdempotencyRejection.MissingKey}: 422</li>
 *   <li>{@link IdempotencyRejection.KeyTooLong}: 422</li>
 *   <li>{@link IdempotencyRejection.BodyTooLarge}: 413</li>
 *   <li>{@link IdempotencyRejection.FingerprintMismatch}: 422</li>
 *   <li>{@link IdempotencyRejection.InFlight}: its suggested status, 409 unless configured</li>
 * </ul>
 *
 * <p>Public so a custom handler can render a few cases and delegate the rest:
 *
 * <pre>{@code
 * class ApiRejections implements IdempotencyRejectionHandler {
 *     private final IdempotencyRejectionHandler defaults = new DefaultIdempotencyRejectionHandler();
 *
 *     public void handle(IdempotencyRejection rejection, HttpServletRequest request, HttpServletResponse response)
 *             throws IOException {
 *         if (rejection instanceof IdempotencyRejection.InFlight inFlight) {
 *             writeApiError(response, inFlight.suggestedStatus(), "IN_FLIGHT");
 *         } else {
 *             defaults.handle(rejection, request, response);
 *         }
 *     }
 * }
 * }</pre>
 */
public class DefaultIdempotencyRejectionHandler implements IdempotencyRejectionHandler {

    @Override
    public void handle(IdempotencyRejection rejection, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        switch (rejection) {
            case IdempotencyRejection.MissingKey ignored ->
                HttpIdempotencyMapper.writeJsonError(response, 422, "Idempotency-Key header is required");
            case IdempotencyRejection.KeyTooLong tooLong ->
                HttpIdempotencyMapper.writeJsonError(
                        response, 422, "Idempotency-Key must not exceed " + tooLong.maxLength() + " characters");
            case IdempotencyRejection.BodyTooLarge ignored ->
                HttpIdempotencyMapper.writeJsonError(response, 413, "Request body exceeds maximum allowed size");
            case IdempotencyRejection.FingerprintMismatch ignored ->
                HttpIdempotencyMapper.writeJsonError(
                        response, 422, "Idempotency-Key reused with a different request body");
            case IdempotencyRejection.InFlight inFlight ->
                HttpIdempotencyMapper.writeJsonError(
                        response, inFlight.suggestedStatus(), "Request with this key is already being processed");
        }
    }
}
