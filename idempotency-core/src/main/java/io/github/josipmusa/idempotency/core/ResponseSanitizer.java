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

/**
 * SPI for sanitizing {@link StoredResponse} instances before they are persisted.
 *
 * <p>Called by the adapter layer (e.g. {@code IdempotencyFilter}) immediately
 * before {@link IdempotencyStore#complete}. The returned value is what gets
 * stored and replayed on duplicate requests.
 *
 */
@FunctionalInterface
public interface ResponseSanitizer {

    /**
     * Sanitizes the given response before storage.
     *
     * @param response the captured response; never {@code null}
     * @return the sanitized response to store; must not be {@code null}
     */
    StoredResponse sanitize(StoredResponse response);
}
