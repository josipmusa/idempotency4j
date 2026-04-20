/**
 * Spring Boot autoconfiguration for idempotency4j.
 *
 * <p>This package wires all idempotency components together based on classpath detection.
 * Applications should not reference these classes directly — they are activated
 * automatically when the {@code idempotency-spring-boot-starter} artifact is on the
 * classpath and an {@link io.github.josipmusa.idempotency.core.IdempotencyStore} bean is present.
 *
 * <ul>
 *   <li>{@link io.github.josipmusa.idempotency.springboot.IdempotencyAutoConfiguration} —
 *       registers the engine, filter, handler registry, and scheduler beans.
 *       All beans are conditional on {@code @ConditionalOnMissingBean} so applications
 *       can override any individual component.</li>
 *   <li>{@link io.github.josipmusa.idempotency.springboot.IdempotencyPurgeAutoConfiguration} —
 *       registers a {@code SchedulingConfigurer} that schedules
 *       {@link io.github.josipmusa.idempotency.core.IdempotencyStore#purgeExpired()} using the cron
 *       expression from {@code idempotency.purge-cron} (default: hourly). Inert if
 *       {@code @EnableScheduling} is not present in the application context.</li>
 *   <li>{@link io.github.josipmusa.idempotency.springboot.IdempotencyProperties} —
 *       configuration properties bound from the {@code idempotency.*} namespace.</li>
 * </ul>
 */
package io.github.josipmusa.idempotency.springboot;
