package io.github.josipmusa.core;

/**
 * SPI for sanitizing {@link StoredResponse} instances before they are persisted.
 *
 * <p>Called by the adapter layer (e.g. {@code IdempotencyFilter}) immediately
 * before {@link IdempotencyStore#complete}. The returned value is what gets
 * stored and replayed on duplicate requests.
 *
 * <p>The default (registered by the Spring Boot starter) is an identity —
 * no sanitization. Override this bean to strip sensitive headers or mask
 * the response body:
 *
 * <pre>{@code
 * @Bean
 * ResponseSanitizer responseSanitizer() {
 *     return response -> new StoredResponse(
 *         response.statusCode(),
 *         response.headers().entrySet().stream()
 *             .filter(e -> !e.getKey().equalsIgnoreCase("Set-Cookie"))
 *             .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)),
 *         response.body(),
 *         response.completedAt()
 *     );
 * }
 * }</pre>
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
