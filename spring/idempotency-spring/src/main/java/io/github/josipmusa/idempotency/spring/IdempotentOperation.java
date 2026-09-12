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
import java.lang.reflect.Method;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.Objects;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * One {@link Idempotent} method, parsed and validated.
 *
 * <p>Resolved once when the proxy is built and cached, so the hot path does no annotation
 * reading, duration parsing, or expression compiling. Anything wrong with the annotation -
 * an unparseable duration, an over-long scope, a value-returning method with no codec -
 * fails here, which is startup, rather than on the first message.
 *
 * @param scope          the unit of work the store dedupes under
 * @param key            the compiled SpEL expression producing the idempotency key
 * @param ttl            how long a completed record is kept
 * @param lease          how long the acquisition is protected
 * @param waitTimeout    how long a concurrent caller blocks for an in-flight execution
 * @param completionMode whether the record joins the caller's transaction
 * @param codecBeanName  the {@code PayloadCodec} bean to encode the return value with, or
 *                       {@code null} for a {@code void} method
 */
record IdempotentOperation(
        String scope,
        Expression key,
        Duration ttl,
        Duration lease,
        Duration waitTimeout,
        CompletionMode completionMode,
        String codecBeanName) {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    IdempotentOperation {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(key, "key must not be null");
    }

    static IdempotentOperation of(
            Idempotent annotation, Method method, Class<?> targetClass, IdempotencyConfig defaults) {
        String scope = resolveScope(annotation, method, targetClass);
        return new IdempotentOperation(
                scope,
                parseKey(annotation, method),
                parseDuration(annotation.ttl(), "ttl", defaults.defaultTtl(), method),
                parseDuration(annotation.lease(), "lease", defaults.defaultLeaseDuration(), method),
                parseDuration(annotation.waitTimeout(), "waitTimeout", defaults.defaultWaitTimeout(), method),
                annotation.completion(),
                resolveCodec(annotation, method));
    }

    private static String resolveScope(Idempotent annotation, Method method, Class<?> targetClass) {
        String scope = annotation.scope().isEmpty()
                ? targetClass.getSimpleName() + "." + method.getName()
                : annotation.scope();
        try {
            // The identity is the authority on what a scope may be; build one to borrow its rules.
            new IdempotencyIdentity(scope, "probe");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid idempotency scope '" + scope + "' on " + describe(method) + ": " + e.getMessage(), e);
        }
        return scope;
    }

    private static Expression parseKey(Idempotent annotation, Method method) {
        if (annotation.key().isEmpty()) {
            throw new IllegalStateException("@Idempotent(key = ...) is required on " + describe(method)
                    + ": a method has no transport to take an idempotency key from, so the key must be an "
                    + "expression over its parameters, for example \"#event.id()\"");
        }
        try {
            return PARSER.parseExpression(annotation.key());
        } catch (ParseException e) {
            throw new IllegalStateException(
                    "Invalid @Idempotent(key = \"" + annotation.key() + "\") on " + describe(method)
                            + ": not a valid SpEL expression",
                    e);
        }
    }

    private static String resolveCodec(Idempotent annotation, Method method) {
        boolean returnsValue = method.getReturnType() != void.class && method.getReturnType() != Void.class;
        String codec = annotation.codec();
        if (!returnsValue) {
            if (!codec.isEmpty()) {
                throw new IllegalStateException("@Idempotent(codec = \"" + codec + "\") on " + describe(method)
                        + " is pointless: a void method has nothing to replay");
            }
            return null;
        }
        if (codec.isEmpty()) {
            throw new IllegalStateException("@Idempotent(codec = ...) is required on " + describe(method)
                    + ": it returns " + method.getReturnType().getSimpleName()
                    + ", and a duplicate call has to be given that value back. Name a PayloadCodec bean that "
                    + "encodes it, or make the method void");
        }
        return codec;
    }

    private static Duration parseDuration(String raw, String attribute, Duration defaultValue, Method method) {
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Duration.parse(raw);
        } catch (DateTimeException e) {
            throw new IllegalStateException(
                    "Invalid @Idempotent(" + attribute + " = \"" + raw + "\") on " + describe(method)
                            + ": not a valid ISO-8601 duration (e.g. \"PT10S\", \"PT5M\", \"PT1H\")",
                    e);
        }
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }
}
