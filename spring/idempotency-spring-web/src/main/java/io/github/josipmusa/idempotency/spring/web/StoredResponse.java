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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A snapshot of an HTTP response stored for idempotent replay.
 *
 * <p>Captured by {@link IdempotencyFilter} after the handler completes and turned into a
 * transport-neutral {@link io.github.josipmusa.idempotency.core.Payload} by
 * {@link StoredResponseCodec}. When a duplicate request arrives, the codec decodes the
 * stored payload back into one of these and the filter replays it to the client.
 *
 * <p>When the original request completed is not part of this type: the store records that
 * and reports it alongside the payload.
 *
 * <p>Headers and body are defensively copied on construction to prevent mutation after
 * storage.
 *
 * <p>{@code equals}, {@code hashCode} and {@code toString} compare and render the body by
 * content rather than by array identity, so two responses carrying the same bytes are equal.
 *
 * @param statusCode the HTTP status code (e.g. 200, 201, 409)
 * @param headers    HTTP response headers — deep-copied so inner lists are immutable
 * @param body       the raw response body bytes — cloned on construction
 */
public record StoredResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {

    public StoredResponse {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(body, "body must not be null");
        headers = headers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StoredResponse other
                && statusCode == other.statusCode
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(statusCode, headers, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "StoredResponse[statusCode=" + statusCode + ", headers=" + headers + ", body=" + body.length + " bytes]";
    }
}
