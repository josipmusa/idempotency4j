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
package io.github.josipmusa.idempotency.spring;

import io.github.josipmusa.idempotency.core.CompletionMode;
import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import io.github.josipmusa.idempotency.core.IdempotencyIdentity;
import io.github.josipmusa.idempotency.core.PayloadCodec;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a method at most once per idempotency key.
 *
 * <p>Transport-neutral: the same annotation covers an {@code @EventListener}, a
 * {@code @KafkaListener}, a plain service method, and an MVC handler. What differs is who
 * supplies the key. A method-level interceptor reads it from {@link #key()}; the HTTP filter
 * takes it from the request header and uses this annotation only for the durations and the
 * scope.
 *
 * <pre>{@code
 * @Idempotent(key = "#event.id()", waitTimeout = "PT0S")
 * void on(OrderPlaced event) { ... }
 * }</pre>
 *
 * <p>Every duration is an ISO-8601 string ({@code "PT30S"}, {@code "PT1H"}); an empty string
 * means "use the {@link IdempotencyConfig} default". Invalid values are rejected at startup,
 * not on the first request.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * SpEL expression producing the idempotency key, evaluated over the method's parameters:
     * {@code "#event.id()"}, {@code "#publicationId"}, {@code "#p0"}.
     *
     * <p>Required for method-level interception, where there is no transport to take a key
     * from; it must resolve to a non-blank value at most
     * {@value IdempotencyIdentity#MAX_KEY_LENGTH} characters. The HTTP filter ignores it -
     * the key of an HTTP request is the client's header.
     *
     * @return the key expression
     */
    String key() default "";

    /**
     * The unit of work this method is, and half of what the store dedupes on.
     *
     * <p>Empty means {@code <simple class name>.<method name>} - {@code OrderListener.on} -
     * which keeps two consumers of the same message independent. Set it explicitly to keep
     * the scope stable across a rename, or to make two methods share one record. May not
     * contain {@code ':'}; see {@link IdempotencyIdentity}.
     *
     * @return the scope, or empty for the default
     */
    String scope() default "";

    /**
     * How long a completed record is kept before the key can be reused - the deduplication
     * window.
     *
     * @return an ISO-8601 duration, or empty for {@link IdempotencyConfig#defaultTtl()}
     */
    String ttl() default "";

    /**
     * How long this execution's acquisition is protected before another caller may steal it.
     * The heartbeat extends it at half this interval, so it also sets how long a crashed
     * holder blocks the key.
     *
     * @return an ISO-8601 duration, or empty for {@link IdempotencyConfig#defaultLeaseDuration()}
     */
    String lease() default "";

    /**
     * How long a concurrent caller blocks waiting for an in-flight execution before it is
     * told to come back later. {@code "PT0S"} means do not block, which is what a message
     * listener wants: declining a redelivery is cheap, parking a consumer thread is not.
     *
     * <p>Named {@code waitTimeout} rather than {@code wait} because an annotation element
     * cannot be called {@code wait} - it would clash with {@link Object#wait()}.
     *
     * @return an ISO-8601 duration, or empty for {@link IdempotencyConfig#defaultWaitTimeout()}
     */
    String waitTimeout() default "";

    /**
     * Whether the record is written on its own or inside the transaction the method is
     * already running in.
     *
     * <p>{@link CompletionMode#JOIN_TRANSACTION} needs an active transaction when the method
     * is entered - put {@code @Transactional} outside this annotation's interceptor - and a
     * store that supports it.
     *
     * @return the completion mode
     */
    CompletionMode completion() default CompletionMode.AUTONOMOUS;

    /**
     * Name of the {@link PayloadCodec} bean that turns this method's return value into a
     * stored payload and back, so a duplicate call gets the original answer.
     *
     * <p>Required for a method that returns a value, and rejected at startup when missing:
     * this module does not guess at a serialisation format. A {@code void} method needs none
     * - there is nothing to replay - and must leave it empty.
     *
     * @return the codec bean name, or empty for a {@code void} method
     */
    String codec() default "";
}
