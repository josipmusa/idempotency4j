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

import static io.github.josipmusa.idempotency.spring.web.IdempotentHandlerRegistry.*;

import io.github.josipmusa.idempotency.core.ExecutionResult;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyEngine;
import io.github.josipmusa.idempotency.core.IdempotencyIdentity;
import io.github.josipmusa.idempotency.core.NoPayload;
import io.github.josipmusa.idempotency.core.StoredResponse;
import io.github.josipmusa.idempotency.core.exception.IdempotencyDurabilityException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyFingerprintMismatchException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyLeaseLostException;
import io.github.josipmusa.idempotency.core.exception.IdempotencyLockTimeoutException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
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
 * either stores the new response or replays the stored one for duplicates. All Servlet-level
 * translation lives in {@link HttpIdempotencyMapper}.
 *
 * <p>The record's scope is the resolved handler method, {@code <simple class name>.<method name>},
 * so the same {@code Idempotency-Key} sent to two endpoints is two independent records. See
 * {@link IdempotentHandlerRegistry}.
 *
 * <p>Do not annotate with {@code @Component} or {@code @Bean} — wiring belongs in the starter.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private static final String ERROR_MISSING_KEY = "Idempotency-Key header is required";
    private static final String ERROR_KEY_TOO_LONG = "Idempotency-Key must not exceed 255 characters";
    private static final String ERROR_LOCK_TIMEOUT = "Request with this key is already being processed";
    private static final String ERROR_FINGERPRINT_MISMATCH = "Idempotency-Key reused with a different request body";
    private static final String ERROR_BODY_TOO_LARGE = "Request body exceeds maximum allowed size";
    private static final long NO_LIMIT = -1;

    private final IdempotencyEngine engine;
    private final WebIdempotencyConfig config;
    private final RequestMappingHandlerMapping handlerMapping;
    private final IdempotentHandlerRegistry registry;
    private final long maxBodyBytes;
    private final ResponseSanitizer sanitizer;
    private final Clock clock;

    public IdempotencyFilter(
            IdempotencyEngine engine,
            WebIdempotencyConfig config,
            RequestMappingHandlerMapping handlerMapping,
            IdempotentHandlerRegistry registry,
            long maxBodyBytes,
            ResponseSanitizer sanitizer,
            Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.handlerMapping = Objects.requireNonNull(handlerMapping, "handlerMapping must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.maxBodyBytes = maxBodyBytes;
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public IdempotencyFilter(
            IdempotencyEngine engine,
            WebIdempotencyConfig config,
            RequestMappingHandlerMapping handlerMapping,
            IdempotentHandlerRegistry registry,
            long maxBodyBytes,
            ResponseSanitizer sanitizer) {
        this(engine, config, handlerMapping, registry, maxBodyBytes, sanitizer, Clock.systemUTC());
    }

    public IdempotencyFilter(
            IdempotencyEngine engine,
            WebIdempotencyConfig config,
            RequestMappingHandlerMapping handlerMapping,
            IdempotentHandlerRegistry registry,
            long maxBodyBytes) {
        this(engine, config, handlerMapping, registry, maxBodyBytes, response -> response, Clock.systemUTC());
    }

    public IdempotencyFilter(
            IdempotencyEngine engine,
            WebIdempotencyConfig config,
            RequestMappingHandlerMapping handlerMapping,
            IdempotentHandlerRegistry registry) {
        this(engine, config, handlerMapping, registry, NO_LIMIT, response -> response, Clock.systemUTC());
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
                HttpIdempotencyMapper.writeJsonError(response, 422, ERROR_MISSING_KEY);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (key.length() > IdempotencyIdentity.MAX_KEY_LENGTH) {
            HttpIdempotencyMapper.writeJsonError(response, 422, ERROR_KEY_TOO_LONG);
            return;
        }

        ReplayableBodyRequestWrapper wrappedRequest =
                ReplayableBodyRequestWrapper.buffer(request, maxBodyBytes == NO_LIMIT ? NO_LIMIT : maxBodyBytes + 1);
        if (maxBodyBytes != NO_LIMIT && wrappedRequest.body().length > maxBodyBytes) {
            HttpIdempotencyMapper.writeJsonError(response, 413, ERROR_BODY_TOO_LARGE);
            return;
        }
        String fingerprint = RequestFingerprint.of(wrappedRequest.body());

        IdempotencyContext context = new IdempotencyContext(
                resolvedIdempotent.scope(),
                key,
                resolvedIdempotent.ttl(),
                resolvedIdempotent.lockTimeout(),
                fingerprint);

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        ExecutionResult result;
        try {
            result = engine.execute(context, () -> chain.doFilter(wrappedRequest, wrappedResponse));
        } catch (IdempotencyFingerprintMismatchException e) {
            HttpIdempotencyMapper.writeJsonError(response, 422, ERROR_FINGERPRINT_MISMATCH);
            return;
        } catch (IdempotencyLockTimeoutException e) {
            HttpIdempotencyMapper.writeJsonError(
                    response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ERROR_LOCK_TIMEOUT);
            return;
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }

        switch (result) {
            case ExecutionResult.Executed executed -> storeAndFlush(context, executed.leaseId(), wrappedResponse);
            case ExecutionResult.Duplicate duplicate -> {
                switch (duplicate.payload()) {
                    case StoredResponse stored -> HttpIdempotencyMapper.replay(stored, response);
                    // Only a non-HTTP caller stores NoPayload under a key, so this cannot arise
                    // from a record this filter created. Answer 204 rather than fail the request.
                    case NoPayload ignored -> {
                        log.warn(
                                "Idempotency record {} was completed by a non-HTTP caller with no response to replay; answering 204",
                                context.identity());
                        HttpIdempotencyMapper.replayEmpty(response);
                    }
                }
            }
        }
    }

    /**
     * Stores the captured response and flushes the buffered body to the client.
     *
     * <p>The action already ran and its side effects are durable, so a storage failure must
     * not fail the request: it is logged and the response is returned as normal. A duplicate
     * arriving later will re-execute rather than replay.
     */
    private void storeAndFlush(IdempotencyContext context, String leaseId, ContentCachingResponseWrapper wrapped)
            throws IOException {
        StoredResponse captured = HttpIdempotencyMapper.capture(wrapped, Instant.now(clock));
        try {
            StoredResponse sanitized = sanitizer.sanitize(captured);
            try {
                engine.complete(context, leaseId, sanitized, context.ttl());
            } catch (IdempotencyDurabilityException e) {
                log.error(
                        "Stored idempotency response for {}, but requested durability was not confirmed; storage state is indeterminate",
                        context.identity(),
                        e);
            } catch (IdempotencyLeaseLostException e) {
                log.error(
                        "Could not store idempotency response for {} because this execution no longer owns the lease",
                        context.identity(),
                        e);
            } catch (Exception e) {
                log.error(
                        "Failed while storing idempotency response for {}; storage state is indeterminate",
                        context.identity(),
                        e);
            }
        } catch (Exception e) {
            log.error("Failed to sanitize idempotency response for {}; response was not stored", context.identity(), e);
        } finally {
            wrapped.copyBodyToResponse();
        }
    }

    @Nullable
    private HandlerMethod resolveHandlerMethod(HttpServletRequest request) {
        HandlerExecutionChain handlerChain;
        try {
            handlerChain = handlerMapping.getHandler(request);
        } catch (Exception e) {
            log.warn(
                    "Could not resolve handler for request [{} {}]; skipping idempotency enforcement",
                    request.getMethod(),
                    request.getRequestURI(),
                    e);
            return null;
        }
        if (handlerChain == null || !(handlerChain.getHandler() instanceof HandlerMethod hm)) {
            return null;
        }
        return hm;
    }
}
