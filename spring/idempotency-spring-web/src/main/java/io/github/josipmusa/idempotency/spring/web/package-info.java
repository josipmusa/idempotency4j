/**
 * Spring MVC adapter for the idempotency engine.
 *
 * <p>The HTTP half of the Spring integration: everything here is about Servlet requests and
 * responses. The transport-neutral pieces - the {@code @Idempotent} annotation itself, the
 * method interceptor, transaction participation - live in
 * {@code io.github.josipmusa.idempotency.spring}.
 *
 * <ul>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.IdempotencyFilter} — resolves the
 *       handler, builds the {@link io.github.josipmusa.idempotency.core.IdempotencyContext}
 *       from the request, hands the engine a supplier that runs the rest of the chain, and
 *       turns the returned {@link io.github.josipmusa.idempotency.core.Outcome} back into a
 *       response.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.IdempotentHandlerRegistry} —
 *       resolves every {@link io.github.josipmusa.idempotency.spring.Idempotent} handler
 *       method at startup, so the hot path is a map lookup and a bad annotation fails the
 *       context.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.HttpIdempotencyMapper} — the
 *       Servlet translation: capturing a response, replaying one, writing an error.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.StoredResponse} and its codec —
 *       how an HTTP response is carried in a
 *       {@link io.github.josipmusa.idempotency.core.Payload}.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.RequestFingerprint} — computes
 *       a SHA-256 hex digest of the request body for fingerprint mismatch detection.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.WebIdempotencyConfig} — HTTP-only
 *       settings: the key header, the in-flight status, and whether a key is required.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.ResponseSanitizer} — SPI for
 *       scrubbing sensitive data from responses before they are persisted.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.IdempotencyRejectionHandler} — SPI
 *       that writes the responses the filter answers itself, one
 *       {@link io.github.josipmusa.idempotency.spring.web.IdempotencyRejection} case each;
 *       {@link io.github.josipmusa.idempotency.spring.web.DefaultIdempotencyRejectionHandler}
 *       writes the {@code {"error": "..."}} bodies.</li>
 * </ul>
 *
 * <p>The engine owns the record's whole lifecycle, completion included; the filter never
 * touches the store.
 */
package io.github.josipmusa.idempotency.spring.web;
