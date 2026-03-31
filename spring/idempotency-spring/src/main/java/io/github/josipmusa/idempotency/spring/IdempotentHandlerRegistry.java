package io.github.josipmusa.idempotency.spring;

import io.github.josipmusa.core.IdempotencyConfig;
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
 * <p>Fails fast at startup if any annotation contains an invalid ISO-8601 duration string,
 * preventing misconfiguration from reaching production traffic.
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
            Duration ttl = parseDuration(annotation.ttl(), "ttl", config.defaultTtl(), handlerMethod);
            Duration lockTimeout =
                    parseDuration(annotation.lockTimeout(), "lockTimeout", config.defaultLockTimeout(), handlerMethod);
            builtAnnotationCache.put(method, new ResolvedIdempotent(annotation.required(), ttl, lockTimeout));
        });
        this.cache = Map.copyOf(builtAnnotationCache);
    }

    @Nullable
    public ResolvedIdempotent resolve(HandlerMethod handlerMethod) {
        return cache.get(handlerMethod.getMethod());
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

    public record ResolvedIdempotent(boolean required, Duration ttl, Duration lockTimeout) {}
}
