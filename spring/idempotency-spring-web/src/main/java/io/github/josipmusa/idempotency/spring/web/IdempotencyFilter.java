package io.github.josipmusa.idempotency.spring.web;

import static io.github.josipmusa.idempotency.spring.web.IdempotentHandlerRegistry.*;

import io.github.josipmusa.core.ExecutionResult;
import io.github.josipmusa.core.IdempotencyConfig;
import io.github.josipmusa.core.IdempotencyContext;
import io.github.josipmusa.core.IdempotencyEngine;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.core.StoredResponse;
import io.github.josipmusa.core.exception.IdempotencyFingerprintMismatchException;
import io.github.josipmusa.core.exception.IdempotencyLockTimeoutException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
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

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private static final String HEADER_IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    private static final String ERROR_MISSING_KEY = "Idempotency-Key header is required";
    private static final String ERROR_KEY_TOO_LONG = "Idempotency-Key must not exceed 255 characters";
    private static final String ERROR_LOCK_TIMEOUT = "Request with this key is already being processed";
    private static final String ERROR_FINGERPRINT_MISMATCH = "Idempotency-Key reused with a different request body";
    private static final String ERROR_BODY_TOO_LARGE = "Request body exceeds maximum allowed size";
    private static final long NO_LIMIT = -1;

    private final IdempotencyEngine engine;
    private final IdempotencyStore store;
    private final IdempotencyConfig config;
    private final RequestMappingHandlerMapping handlerMapping;
    private final IdempotentHandlerRegistry registry;
    private final long maxBodyBytes;

    public IdempotencyFilter(
            IdempotencyEngine engine,
            IdempotencyStore store,
            IdempotencyConfig config,
            RequestMappingHandlerMapping handlerMapping,
            IdempotentHandlerRegistry registry,
            long maxBodyBytes) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.handlerMapping = Objects.requireNonNull(handlerMapping, "handlerMapping must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.maxBodyBytes = maxBodyBytes;
    }

    public IdempotencyFilter(
            IdempotencyEngine engine,
            IdempotencyStore store,
            IdempotencyConfig config,
            RequestMappingHandlerMapping handlerMapping,
            IdempotentHandlerRegistry registry) {
        this(engine, store, config, handlerMapping, registry, NO_LIMIT);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        HandlerMethod handlerMethod = resolveHandlerMethod(request);
        if (handlerMethod == null) {
            chain.doFilter(request, response);
            return;
        }

        ResolvedIdempotent resolvedIdempotent = registry.resolve(handlerMethod);
        if (resolvedIdempotent == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = request.getHeader(config.keyHeader());
        if (key == null || key.isBlank()) {
            if (resolvedIdempotent.required()) {
                writeJsonError(response, 422, ERROR_MISSING_KEY);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (key.length() > IdempotencyContext.MAX_KEY_LENGTH) {
            writeJsonError(response, 422, ERROR_KEY_TOO_LONG);
            return;
        }

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        // Read the body into the cache. When a size limit is configured, read only up to
        // limit + 1 bytes so we detect oversized bodies without buffering the full payload.
        if (maxBodyBytes != NO_LIMIT) {
            long safeLimit = maxBodyBytes >= Integer.MAX_VALUE ? Integer.MAX_VALUE : maxBodyBytes + 1;
            wrappedRequest.getInputStream().readNBytes((int) safeLimit);
            if (wrappedRequest.getContentAsByteArray().length > maxBodyBytes) {
                writeJsonError(response, 413, ERROR_BODY_TOO_LARGE);
                return;
            }
        } else {
            wrappedRequest.getInputStream().readAllBytes();
        }
        String fingerprint = RequestFingerprint.of(wrappedRequest.getContentAsByteArray());

        IdempotencyContext context =
                new IdempotencyContext(key, resolvedIdempotent.ttl(), resolvedIdempotent.lockTimeout(), fingerprint);

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        ExecutionResult result;
        try {
            result = engine.execute(context, () -> chain.doFilter(wrappedRequest, wrappedResponse));
        } catch (IdempotencyFingerprintMismatchException e) {
            writeJsonError(response, 422, ERROR_FINGERPRINT_MISMATCH);
            return;
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
                stored.headers().forEach((name, values) -> {
                    if (name.equalsIgnoreCase("content-type")) {
                        response.setContentType(values.getFirst());
                    } else {
                        values.forEach(value -> response.addHeader(name, value));
                    }
                });
                response.setHeader(HEADER_IDEMPOTENT_REPLAYED, "true");
                response.setHeader("Cache-Control", "no-store");
                response.setContentLength(stored.body().length);
                response.getOutputStream().write(stored.body());
            }
        }
    }

    @Nullable
    private HandlerMethod resolveHandlerMethod(HttpServletRequest request) {
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
        if (handlerChain == null || !(handlerChain.getHandler() instanceof HandlerMethod hm)) {
            return null;
        }
        return hm;
    }

    private Map<String, List<String>> collectHeaders(ContentCachingResponseWrapper response) {
        Map<String, List<String>> headers = new HashMap<>();
        response.getHeaderNames().forEach(name -> headers.put(name, new ArrayList<>(response.getHeaders(name))));

        // Content-Type may be set directly on the response, not surfaced via getHeaderNames()
        String contentType = response.getContentType();
        if (contentType != null && headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("content-type"))) {
            headers.put("Content-Type", List.of(contentType));
        }
        return headers;
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\": \"" + escapeJson(message) + "\"}");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }
}
