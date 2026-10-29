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
 * SPI that writes the response for a request {@link IdempotencyFilter} rejects itself.
 *
 * <p>Use it when an API has its own error contract: the default,
 * {@link DefaultIdempotencyRejectionHandler}, writes {@code {"error": "..."}} bodies. A handler
 * can switch over every {@link IdempotencyRejection} exhaustively, or render the cases it cares
 * about and delegate the rest to the default. The handler runs instead of the protected endpoint,
 * so nothing is executed or stored for a rejected request.
 */
@FunctionalInterface
public interface IdempotencyRejectionHandler {

    /**
     * Writes the response for a rejected request.
     *
     * @param rejection why the request was rejected; never {@code null}
     * @param request   the rejected request
     * @param response  the response to write; for {@link IdempotencyRejection.InFlight} it already
     *                  carries {@code Retry-After}
     * @throws IOException if the response cannot be written
     */
    void handle(IdempotencyRejection rejection, HttpServletRequest request, HttpServletResponse response)
            throws IOException;
}
