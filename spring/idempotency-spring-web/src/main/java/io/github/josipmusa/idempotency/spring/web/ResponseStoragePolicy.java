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

/**
 * SPI that decides whether a fresh response is stored for duplicates to replay, or the key is
 * released so the client can retry it.
 *
 * <p>{@link IdempotencyFilter} asks it once per fresh execution, after the handler wrote its
 * response and before the response is sanitized and encoded. A replay and the filter's own
 * rejections never reach it. Either way the client gets the handler's response, once.
 *
 * <p>Returning {@code false} is permission to run the operation again under the same key, with
 * any request body: the record is deleted and the next request executes as if the key had never
 * been used. It does not undo anything the handler did, and it does not roll back a transaction,
 * so return it only for a response that means nothing happened, such as a refusal before any
 * downstream call. A status alone rarely says that: a 504 after the downstream system received
 * the request must be stored, or the retry runs the operation twice. Let the code that knows
 * mark the request, for example with an attribute set by its exception handler:
 *
 * <pre>{@code
 * @Bean
 * ResponseStoragePolicy responseStoragePolicy() {
 *     return (request, response) -> request.getAttribute("idempotency.release") == null;
 * }
 *
 * // in an exception handler, when nothing was sent downstream:
 * request.setAttribute("idempotency.release", true);
 * }</pre>
 *
 * <p>Only an explicit {@code false} releases the key. A policy that throws is treated as a
 * failure to record the completion: the configured
 * {@link io.github.josipmusa.idempotency.core.CompletionFailurePolicy} applies and the record is
 * kept.
 */
@FunctionalInterface
public interface ResponseStoragePolicy {

    /** Stores every response, which is what the filter does without a policy. */
    ResponseStoragePolicy ALWAYS = (request, response) -> true;

    /**
     * Decides what happens to a fresh response.
     *
     * @param request  the request the handler answered
     * @param response what the handler wrote, before sanitization
     * @return {@code true} to store it, {@code false} to release the key instead
     */
    boolean shouldStore(HttpServletRequest request, StoredResponse response);
}
