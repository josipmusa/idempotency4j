package io.github.josipmusa.idempotency.spring;

import io.github.josipmusa.core.ExecutionResult;
import io.github.josipmusa.core.IdempotencyConfig;
import io.github.josipmusa.core.IdempotencyContext;
import io.github.josipmusa.core.IdempotencyEngine;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.core.StoredResponse;
import io.github.josipmusa.core.exception.IdempotencyLockTimeoutException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Spring MVC filter that enforces idempotency for handler methods annotated with {@link Idempotent}.
 *
 * <p>Uses {@link RequestMappingHandlerMapping} to resolve the handler for each request, then checks
 * for the {@link Idempotent} annotation. If present, it delegates to {@link IdempotencyEngine} and
 * either stores the new response or replays the stored one for duplicates.
 *
 * <p>Do not annotate with {@code @Component} or {@code @Bean} — wiring belongs in the starter.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Log log = LogFactory.getLog(IdempotencyFilter.class);

    private static final String HEADER_IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    private static final String ERROR_MISSING_KEY = "Idempotency-Key header is required";
    private static final String ERROR_KEY_TOO_LONG = "Idempotency-Key must not exceed 255 characters";
    private static final String ERROR_LOCK_TIMEOUT = "Request with this key is already being processed";
    private final IdempotencyEngine engine;
    private final IdempotencyStore store;
    private final IdempotencyConfig config;
    private final RequestMappingHandlerMapping handlerMapping;

    public IdempotencyFilter(
            IdempotencyEngine engine,
            IdempotencyStore store,
            IdempotencyConfig config,
            RequestMappingHandlerMapping handlerMapping) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.handlerMapping = Objects.requireNonNull(handlerMapping, "handlerMapping must not be null");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        Idempotent annotation = resolveAnnotation(request);
        if (annotation == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = request.getHeader(config.keyHeader());
        if (key == null || key.isBlank()) {
            // annotation.required() is authoritative here; config.keyRequired() is for the starter layer
            if (annotation.required()) {
                writeJsonError(response, 422, ERROR_MISSING_KEY);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        Duration ttl = parseDuration(annotation.ttl(), "ttl", config.defaultTtl());
        Duration lockTimeout = parseDuration(annotation.lockTimeout(), "lockTimeout", config.defaultLockTimeout());
        IdempotencyContext context;
        try {
            context = new IdempotencyContext(key, ttl, lockTimeout);
        } catch (IllegalArgumentException e) {
            writeJsonError(response, 422, ERROR_KEY_TOO_LONG);
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        ExecutionResult result;
        try {
            result = engine.execute(context, () -> chain.doFilter(request, wrappedResponse));
        } catch (IdempotencyLockTimeoutException e) {
            writeJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ERROR_LOCK_TIMEOUT);
            return;
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }

        switch (result) {
            case ExecutionResult.Executed ignored -> {
                StoredResponse storedResponse = new StoredResponse(
                        wrappedResponse.getStatus(),
                        collectHeaders(wrappedResponse),
                        wrappedResponse.getContentAsByteArray(),
                        Instant.now());
                try {
                    store.complete(context.key(), storedResponse, context.ttl());
                } catch (Exception e) {
                    log.error(
                            "Failed to store idempotency response for key '"
                                    + context.key()
                                    + "'; key will remain IN_PROGRESS until lock expires",
                            e);
                } finally {
                    wrappedResponse.copyBodyToResponse();
                }
            }
            case ExecutionResult.Duplicate d -> {
                StoredResponse stored = d.response();
                response.setStatus(stored.statusCode());
                stored.headers().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
                response.setHeader(HEADER_IDEMPOTENT_REPLAYED, "true");
                response.getOutputStream().write(stored.body());
            }
        }
    }

    private Idempotent resolveAnnotation(HttpServletRequest request) {
        HandlerExecutionChain handlerChain;
        try {
            handlerChain = handlerMapping.getHandler(request);
        } catch (Exception e) {
            log.warn(
                    "Could not resolve handler for request [" + request.getMethod() + " " + request.getRequestURI()
                            + "]; skipping idempotency enforcement",
                    e);
            return null;
        }
        if (handlerChain == null || !(handlerChain.getHandler() instanceof HandlerMethod handlerMethod)) {
            return null;
        }
        return handlerMethod.getMethodAnnotation(Idempotent.class);
    }

    private Map<String, List<String>> collectHeaders(ContentCachingResponseWrapper response) {
        Map<String, List<String>> headers = new HashMap<>();
        response.getHeaderNames()
                .forEach(
                        name -> headers.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(response.getHeaders(name))));
        return headers;
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + escapeJson(message) + "\"}");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * Parses an ISO-8601 duration string from an {@link Idempotent} annotation attribute.
     * Returns {@code defaultValue} when {@code raw} is empty.
     *
     * <p>Validation is lazy — an invalid duration string will not be caught until the first
     * request hits the annotated handler. The {@link IllegalArgumentException} propagates
     * uncaught, signalling a configuration error in the annotation.
     *
     * @throws IllegalArgumentException if {@code raw} is non-empty but not a valid ISO-8601 duration
     */
    private static Duration parseDuration(String raw, String attributeName, Duration defaultValue) {
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Duration.parse(raw);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "@Idempotent(" + attributeName + " = \"" + raw
                            + "\") is not a valid ISO-8601 duration (e.g. \"PT10S\", \"PT1H\"): "
                            + e.getMessage(),
                    e);
        }
    }
}
