package io.github.josipmusa.idempotency.spring.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring MVC handler method as idempotent.
 *
 * <p>When present, {@link IdempotencyFilter} will enforce idempotency for
 * requests to this endpoint using the configured {@link io.github.josipmusa.core.IdempotencyStore}.
 *
 * <p>Attribute values override {@link io.github.josipmusa.core.IdempotencyConfig} defaults
 * when non-empty.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * ISO-8601 duration for how long a completed response is kept.
     * Empty string means use {@link io.github.josipmusa.core.IdempotencyConfig#defaultTtl()}.
     */
    String ttl() default "";

    /**
     * ISO-8601 duration for how long a concurrent caller waits.
     * Empty string means use {@link io.github.josipmusa.core.IdempotencyConfig#defaultLockTimeout()}.
     */
    String lockTimeout() default "";

    /**
     * Whether a missing idempotency key header should be rejected with 422 Unprocessable Entity.
     * When false, requests without a key pass through without idempotency enforcement.
     */
    boolean required() default true;
}
