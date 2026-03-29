package io.github.josipmusa.core;

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
}
