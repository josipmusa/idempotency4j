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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring MVC handler method as idempotent.
 *
 * <p>When present, {@link IdempotencyFilter} will enforce idempotency for
 * requests to this endpoint using the configured {@link io.github.josipmusa.idempotency.core.IdempotencyStore}.
 *
 * <p>Attribute values override {@link io.github.josipmusa.idempotency.core.IdempotencyConfig} defaults
 * when non-empty.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * ISO-8601 duration for how long a completed response is kept.
     * Empty string means use {@link io.github.josipmusa.idempotency.core.IdempotencyConfig#defaultTtl()}.
     */
    String ttl() default "";

    /**
     * ISO-8601 duration for how long a concurrent caller waits.
     * Empty string means use {@link io.github.josipmusa.idempotency.core.IdempotencyConfig#defaultLockTimeout()}.
     */
    String lockTimeout() default "";

    /**
     * Whether a missing idempotency key header should be rejected with 422 Unprocessable Entity.
     *
     * <p>Full behavior matrix:
     * <table>
     *   <caption>required vs key presence</caption>
     *   <tr><th>required</th><th>Key header present</th><th>Behavior</th></tr>
     *   <tr><td>true (default)</td><td>Yes</td><td>Full idempotency enforcement</td></tr>
     *   <tr><td>true (default)</td><td>No</td><td>422 Unprocessable Entity</td></tr>
     *   <tr><td>false</td><td>Yes</td><td>Full idempotency enforcement</td></tr>
     *   <tr><td>false</td><td>No</td><td>Request passes through without idempotency enforcement</td></tr>
     * </table>
     *
     * <p>Use {@code required = false} on endpoints where idempotency is optional —
     * clients that want it send a key; clients that do not are not rejected.
     */
    boolean required() default true;
}
