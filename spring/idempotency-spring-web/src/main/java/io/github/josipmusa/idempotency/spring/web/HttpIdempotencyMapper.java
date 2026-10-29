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

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Translates between the Servlet API and the transport-neutral core types.
 *
 * <p>Everything HTTP-shaped about the idempotency flow lives here: capturing a
 * {@link StoredResponse} from a completed request, writing one back on replay, and
 * emitting error responses. {@link IdempotencyFilter} keeps only the orchestration.
 *
 * <p>Hop-by-hop headers (RFC 7230 §6.1) are dropped in both directions — they describe
 * the connection that carried the original response, not the resource, so replaying them
 * onto a different connection would be wrong.
 */
final class HttpIdempotencyMapper {

    static final String HEADER_IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private static final Set<String> NON_REPLAYABLE_HEADERS = Set.of(
            "connection",
            "content-length",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private HttpIdempotencyMapper() {}

    /**
     * Snapshots what the handler wrote, ready for encoding and storage.
     *
     * @param response the wrapper that buffered the handler's output
     * @return the captured response
     */
    static StoredResponse capture(ContentCachingResponseWrapper response) {
        return new StoredResponse(response.getStatus(), collectHeaders(response), response.getContentAsByteArray());
    }

    /**
     * Writes a previously stored response back to the client, marked as a replay.
     *
     * @param stored   the response captured by the original request
     * @param response the response to write to
     * @throws IOException if the body cannot be written
     */
    static void replay(StoredResponse stored, HttpServletResponse response) throws IOException {
        Set<String> nonReplayable = nonReplayableHeaders(stored.headers());
        response.setStatus(stored.statusCode());
        stored.headers().forEach((name, values) -> {
            if (!isReplayableHeader(name, nonReplayable)) {
                return;
            }
            if (name.equalsIgnoreCase("content-type")) {
                response.setContentType(values.getFirst());
            } else {
                values.forEach(value -> response.addHeader(name, value));
            }
        });
        markReplayed(response);
        response.setContentLength(stored.body().length);
        response.getOutputStream().write(stored.body());
    }

    /**
     * Marks a response as an idempotent replay without writing a body. Used for a
     * duplicate whose stored payload carries nothing to replay.
     *
     * @param response the response to mark
     */
    static void replayEmpty(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        markReplayed(response);
    }

    /**
     * How long a client should wait before retrying a key another caller is still holding.
     *
     * <p>A pacing heuristic, not an estimate of when the holder will finish. The key replays as
     * soon as the holder completes, and a retry waits up to its own {@code waitTimeout} on the
     * server, so pacing retries at the wait timeout lets a client pick up the result soon after
     * it exists. A retry can still find the key in flight, and get another in-flight response,
     * when the holder runs longer. The holder's remaining lease is the bound before the key can
     * be taken over from a holder that died, unless the lease is renewed, so a shorter remaining
     * lease wins.
     *
     * <p>The tradeoff: earlier retries make the API more responsive, but can send more duplicate
     * requests while the holder is still running. The whole remaining lease, used before, sent
     * fewer duplicates but kept clients waiting long after the result was ready.
     *
     * @param remainingLease the holder's remaining lease
     * @param waitTimeout    the rejected request's wait timeout
     * @return the smaller of the two
     */
    static Duration retryAfter(Duration remainingLease, Duration waitTimeout) {
        return remainingLease.compareTo(waitTimeout) < 0 ? remainingLease : waitTimeout;
    }

    /**
     * Sets {@code Retry-After} on the response to a request whose key another caller is still holding.
     *
     * <p>The header carries {@code retryAfter} in whole seconds, rounded up, and floored at 1 so a
     * store that cannot tell still gets a usable hint rather than an immediate retry. Set before
     * the {@link IdempotencyRejectionHandler} runs, so a handler that only changes the body keeps
     * it.
     *
     * @param response   the response to set the header on
     * @param retryAfter when to retry, from {@link #retryAfter(Duration, Duration)}
     */
    static void setRetryAfter(HttpServletResponse response, Duration retryAfter) {
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        response.setHeader("Retry-After", Long.toString(seconds));
    }

    /**
     * Writes a JSON error body with the given status.
     *
     * @param response the response to write to
     * @param status   the HTTP status code
     * @param message  the human-readable error message
     * @throws IOException if the body cannot be written
     */
    static void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\": \"" + escapeJson(message) + "\"}");
    }

    private static void markReplayed(HttpServletResponse response) {
        response.setHeader(HEADER_IDEMPOTENT_REPLAYED, "true");
        response.setHeader("Cache-Control", "no-store");
    }

    private static Map<String, List<String>> collectHeaders(ContentCachingResponseWrapper response) {
        Map<String, List<String>> headers = new HashMap<>();
        Set<String> nonReplayable = new HashSet<>(NON_REPLAYABLE_HEADERS);
        response.getHeaders("Connection").forEach(value -> addConnectionOptions(nonReplayable, value));
        response.getHeaderNames().stream()
                .filter(name -> isReplayableHeader(name, nonReplayable))
                .forEach(name -> headers.put(name, new ArrayList<>(response.getHeaders(name))));

        String contentType = response.getContentType();
        if (contentType != null && headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("content-type"))) {
            headers.put("Content-Type", List.of(contentType));
        }
        return headers;
    }

    private static Set<String> nonReplayableHeaders(Map<String, List<String>> headers) {
        Set<String> result = new HashSet<>(NON_REPLAYABLE_HEADERS);
        headers.forEach((name, values) -> {
            if (name.equalsIgnoreCase("Connection")) {
                values.forEach(value -> addConnectionOptions(result, value));
            }
        });
        return result;
    }

    private static void addConnectionOptions(Set<String> target, String value) {
        for (String option : value.split(",")) {
            if (!option.isBlank()) {
                target.add(option.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static boolean isReplayableHeader(String name, Set<String> nonReplayable) {
        return !nonReplayable.contains(name.toLowerCase(Locale.ROOT));
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }
}
