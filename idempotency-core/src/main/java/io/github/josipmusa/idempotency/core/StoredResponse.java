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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A snapshot of an HTTP response stored for idempotent replay.
 *
 * <p>Captured by the adapter after the action completes and passed to
 * {@link IdempotencyStore#complete}. When a duplicate request arrives,
 * the store returns this via {@link AcquireResult.Duplicate} and the
 * adapter replays it to the client.
 *
 * <p>Headers and body are defensively copied on construction to prevent
 * mutation after storage.
 *
 * @param statusCode  the HTTP status code (e.g. 200, 201, 409)
 * @param headers     HTTP response headers — deep-copied so inner lists are immutable
 * @param body        the raw response body bytes — cloned on construction
 * @param completedAt when the original request completed, useful for
 *                    debugging and audit logs
 */
public record StoredResponse(int statusCode, Map<String, List<String>> headers, byte[] body, Instant completedAt) {

    public StoredResponse {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        headers = headers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
