/**
 * Spring MVC adapter for the idempotency engine.
 *
 * <p>This package bridges the idempotency core with HTTP. It contains:
 *
 * <ul>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.Idempotent} — annotation
 *       for marking controller methods that require idempotency enforcement.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.IdempotencyFilter} — servlet
 *       filter that intercepts annotated requests, builds the
 *       {@link io.github.josipmusa.idempotency.core.IdempotencyContext}, invokes the engine,
 *       captures the response, and calls {@code store.complete()}.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.IdempotentHandlerRegistry} —
 *       inspects the {@link org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping}
 *       at startup to determine which endpoints are annotated with {@code @Idempotent}.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.web.RequestFingerprint} — computes
 *       a SHA-256 hex digest of the request body for fingerprint mismatch detection.</li>
 * </ul>
 *
 * <p>The adapter is responsible for calling {@code store.complete()} after a successful
 * action. The engine does not call {@code complete} — this boundary is intentional.
 */
package io.github.josipmusa.idempotency.spring.web;
