/**
 * Spring integration for the idempotency engine, with no transport in it.
 *
 * <p>Everything here works the same whether the call arrives as an event, a broker message,
 * or a plain method call; the HTTP-only pieces live in
 * {@code io.github.josipmusa.idempotency.spring.web}, which builds on this package.
 *
 * <ul>
 *   <li>{@link io.github.josipmusa.idempotency.spring.Idempotent} — marks a method as running
 *       at most once per key, and is also what the HTTP filter reads for its durations.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.IdempotentMethodInterceptor} and
 *       {@link io.github.josipmusa.idempotency.spring.IdempotentAdvisor} — the AOP half: they
 *       resolve the identity from the method's arguments and run the body through the
 *       engine.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.OutcomeMapper} — turns the engine's
 *       {@link io.github.josipmusa.idempotency.core.Outcome} into a return value, throwing
 *       {@link io.github.josipmusa.idempotency.spring.IdempotencyInFlightException} when
 *       another caller holds the key.</li>
 *   <li>{@link io.github.josipmusa.idempotency.spring.SpringTransactionParticipation} and
 *       {@link io.github.josipmusa.idempotency.spring.TransactionAwareConnectionResolver} —
 *       what {@link io.github.josipmusa.idempotency.core.CompletionMode#JOIN_TRANSACTION}
 *       needs to write the inbox record inside the caller's own transaction.</li>
 * </ul>
 */
package io.github.josipmusa.idempotency.spring;
