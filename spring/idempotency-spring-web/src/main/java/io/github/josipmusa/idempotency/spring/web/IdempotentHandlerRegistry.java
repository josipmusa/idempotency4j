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

import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import io.github.josipmusa.idempotency.core.IdempotencyIdentity;
import io.github.josipmusa.idempotency.spring.Idempotent;
import java.lang.reflect.Method;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.lang.Nullable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Eagerly resolves and validates all {@link Idempotent}-annotated handler methods at startup,
 * caching the parsed metadata for zero-overhead lookup on the hot path.
 *
 * <p>Each handler method is also given its idempotency <em>scope</em>: {@code <simple class
 * name>.<method name>}, for example {@code PaymentController.create}. The scope is half of the
 * {@link IdempotencyIdentity} the store dedupes on, so the same {@code Idempotency-Key} sent to two
 * handlers is two independent records.
 *
 * <p>Fails fast at startup if any annotation contains an invalid ISO-8601 duration string or a
 * handler's scope exceeds {@link IdempotencyIdentity#MAX_SCOPE_LENGTH}, preventing misconfiguration
 * from reaching production traffic.
 *
 * <p>Do not annotate with {@code @Component} — wiring belongs in the starter.
 */
public class IdempotentHandlerRegistry implements SmartInitializingSingleton {

    private final RequestMappingHandlerMapping handlerMapping;
    private final IdempotencyConfig config;
    private volatile Map<Method, ResolvedIdempotent> cache = Map.of();

    public IdempotentHandlerRegistry(RequestMappingHandlerMapping handlerMapping, IdempotencyConfig config) {
        this.handlerMapping = Objects.requireNonNull(handlerMapping, "handlerMapping must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<Method, ResolvedIdempotent> builtAnnotationCache = new HashMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            Idempotent annotation = handlerMethod.getMethodAnnotation(Idempotent.class);
            if (annotation == null) return;

            Method method = handlerMethod.getMethod();
            rejectMethodOnlyAttributes(annotation, handlerMethod);
            Duration ttl = parseDuration(annotation.ttl(), "ttl", config.defaultTtl(), handlerMethod);
            Duration lease = parseDuration(annotation.lease(), "lease", config.defaultLeaseDuration(), handlerMethod);
            Duration waitTimeout =
                    parseDuration(annotation.waitTimeout(), "waitTimeout", config.defaultWaitTimeout(), handlerMethod);
            String scope = scopeOf(annotation, handlerMethod);
            builtAnnotationCache.put(method, new ResolvedIdempotent(ttl, lease, waitTimeout, scope));
        });
        this.cache = Map.copyOf(builtAnnotationCache);
    }

    @Nullable
    public ResolvedIdempotent resolve(HandlerMethod handlerMethod) {
        return cache.get(handlerMethod.getMethod());
    }

    /**
     * Rejects the {@code @Idempotent} attributes that only a method-level operation can
     * honour, so an endpoint never carries one that quietly does nothing.
     *
     * <p>An HTTP request brings its own key in a header, its own body to fingerprint, and its
     * own response to replay, so {@code key}, {@code codec} and {@code completion} have no
     * meaning here: the filter reads only the durations and the scope. Accepting them and
     * ignoring them would let an endpoint annotated {@code completion = "join-transaction"}
     * look protected while completing autonomously.
     */
    private static void rejectMethodOnlyAttributes(Idempotent annotation, HandlerMethod handlerMethod) {
        rejectAttribute("key", annotation.key(), handlerMethod, "the key comes from the request header");
        rejectAttribute(
                "codec", annotation.codec(), handlerMethod, "the HTTP response itself is what a duplicate replays");
        rejectAttribute(
                "completion",
                annotation.completion(),
                handlerMethod,
                "a request is not running in a transaction the record could join");
    }

    private static void rejectAttribute(String name, String value, HandlerMethod method, String because) {
        if (!value.isEmpty()) {
            throw new IllegalStateException("@Idempotent(" + name + " = \"" + value + "\") on "
                    + method.getShortLogMessage() + " cannot be honoured on an HTTP endpoint: " + because
                    + ". Remove the attribute.");
        }
    }

    private static Duration parseDuration(String raw, String attribute, Duration defaultValue, HandlerMethod method) {
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Duration.parse(raw);
        } catch (DateTimeException e) {
            throw new IllegalStateException(
                    "Invalid @Idempotent(" + attribute + " = \"" + raw + "\") on "
                            + method.getShortLogMessage() + ": not a valid ISO-8601 duration "
                            + "(e.g. \"PT10S\", \"PT5M\", \"PT1H\")",
                    e);
        }
    }

    private static String scopeOf(Idempotent annotation, HandlerMethod handlerMethod) {
        String scope = annotation.scope().isEmpty()
                ? handlerMethod.getBeanType().getSimpleName() + "."
                        + handlerMethod.getMethod().getName()
                : annotation.scope();
        try {
            // The identity is the authority on what a scope may be; build one to borrow its rules.
            new IdempotencyIdentity(scope, "probe");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid idempotency scope '" + scope + "' for " + handlerMethod.getShortLogMessage() + ": "
                            + e.getMessage(),
                    e);
        }
        return scope;
    }

    /**
     * @param ttl      how long the completed response is kept
     * @param lease    how long this request's acquisition is protected before it can be stolen
     * @param waitTimeout how long a concurrent duplicate blocks for the in-flight request
     * @param scope    the handler's idempotency scope, the annotation's own or
     *                 {@code <simple class name>.<method name>}
     */
    public record ResolvedIdempotent(Duration ttl, Duration lease, Duration waitTimeout, String scope) {}
}
