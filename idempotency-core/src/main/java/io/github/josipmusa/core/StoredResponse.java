package io.github.josipmusa.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
 * @param headers     HTTP response headers — copied via {@link Map#copyOf}
 * @param body        the raw response body bytes — cloned on construction
 * @param completedAt when the original request completed, useful for
 *                    debugging and audit logs
 */
public record StoredResponse(int statusCode, Map<String, List<String>> headers, byte[] body, Instant completedAt) {

    public StoredResponse {
        headers = Map.copyOf(headers);
        body = body.clone();
    }
}
