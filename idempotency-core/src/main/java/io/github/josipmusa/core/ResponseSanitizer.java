package io.github.josipmusa.core;

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
