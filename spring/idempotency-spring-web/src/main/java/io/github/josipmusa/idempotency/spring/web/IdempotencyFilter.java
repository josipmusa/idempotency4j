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

import io.github.josipmusa.idempotency.core.*;
import io.github.josipmusa.idempotency.core.exception.IdempotencyFingerprintMismatchException;
import io.github.josipmusa.idempotency.spring.Idempotent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
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
 * for the {@link Idempotent} annotation. If present, it hands the engine a supplier that runs the
 * rest of the chain into a buffering wrapper and returns what the handler wrote. The engine owns
 * the record from there; the filter only turns the {@link Outcome} back into a Servlet response -
 * flush the fresh body, replay the stored one, or reject an in-flight duplicate. All Servlet-level
 * translation lives in {@link HttpIdempotencyMapper}.
 *
 * <p>Because the engine records the completion before {@code execute} returns, the response body
 * reaches the client only once the record is durable. Configure the engine with
 * {@link io.github.josipmusa.idempotency.core.CompletionFailurePolicy#LOG_AND_RETURN} - the
 * starter does - so a storage failure still lets the handler's response through.
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
    private static final String ERROR_IN_FLIGHT = "Request with this key is already being processed";
    private static final String ERROR_FINGERPRINT_MISMATCH = "Idempotency-Key reused with a different request body";
    private static final String ERROR_BODY_TOO_LARGE = "Request body exceeds maximum allowed size";
    private static final long NO_LIMIT = -1;

    private final IdempotencyEngine engine;
    private final WebIdempotencyConfig config;
    private final RequestMappingHandlerMapping handlerMapping;
    private final IdempotentHandlerRegistry registry;
    private final long maxBodyBytes;
    private final StoredResponseCodec codec;

    public IdempotencyFilter(
            IdempotencyEngine engine,
            WebIdempotencyConfig config,
            RequestMappingHandlerMapping handlerMapping,
            IdempotentHandlerRegistry registry,
            long maxBodyBytes,
            ResponseSanitizer sanitizer) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.handlerMapping = Objects.requireNonNull(handlerMapping, "handlerMapping must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.maxBodyBytes = maxBodyBytes;
        this.codec = new StoredResponseCodec(Objects.requireNonNull(sanitizer, "sanitizer must not be null"));
    }

    public IdempotencyFilter(
            IdempotencyEngine engine,
            WebIdempotencyConfig config,
            RequestMappingHandlerMapping handlerMapping,
            IdempotentHandlerRegistry registry,
            long maxBodyBytes) {
        this(engine, config, handlerMapping, registry, maxBodyBytes, response -> response);
    }

    public IdempotencyFilter(
            IdempotencyEngine engine,
            WebIdempotencyConfig config,
            RequestMappingHandlerMapping handlerMapping,
            IdempotentHandlerRegistry registry) {
        this(engine, config, handlerMapping, registry, NO_LIMIT, response -> response);
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
            if (config.required()) {
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

        IdempotencyContext context = IdempotencyContext.builder(resolvedIdempotent.scope(), key)
                .ttl(resolvedIdempotent.ttl())
                .leaseDuration(resolvedIdempotent.lease())
                .waitTimeout(resolvedIdempotent.waitTimeout())
                .fingerprint(fingerprint)
                .build();

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        Outcome<StoredResponse> outcome;
        try {
            outcome = engine.execute(
                    context,
                    () -> {
                        chain.doFilter(wrappedRequest, wrappedResponse);
                        return HttpIdempotencyMapper.capture(wrappedResponse);
                    },
                    replayCodec(context));
        } catch (IdempotencyFingerprintMismatchException e) {
            HttpIdempotencyMapper.writeJsonError(response, 422, ERROR_FINGERPRINT_MISMATCH);
            return;
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }

        switch (outcome) {
            case Outcome.Executed<StoredResponse> ignored -> wrappedResponse.copyBodyToResponse();
            case Outcome.Replayed(StoredResponse stored, Instant ignoredCompletedAt) -> replay(stored, response);
            case Outcome.InFlight(Duration retryAfter) ->
                HttpIdempotencyMapper.writeInFlight(response, config.inFlightStatus(), retryAfter, ERROR_IN_FLIGHT);
        }
    }

    /**
     * The codec the engine uses for this request, tolerant of a record this filter did not write.
     *
     * <p>A payload of any other type was stored under this identity by a non-HTTP caller, so
     * there is no response in it to send. Decoding to {@code null} lets the filter answer 204,
     * which still honours the idempotency guarantee - the action does not run a second time -
     * and is better than failing a request the caller cannot fix. A payload that claims to be
     * an HTTP response but cannot be read is a different matter and still throws.
     */
    private PayloadCodec<StoredResponse> replayCodec(IdempotencyContext context) {
        return new PayloadCodec<>() {
            @Override
            public Payload encode(StoredResponse response) {
                return codec.encode(response);
            }

            @Override
            public StoredResponse decode(Payload payload) {
                if (!StoredResponseCodec.TYPE.equals(payload.type())) {
                    log.warn(
                            "Idempotency record {} holds a '{}' payload with no HTTP response to replay; answering 204",
                            context.identity(),
                            payload.type());
                    return null;
                }
                return codec.decode(payload);
            }
        };
    }

    /**
     * Replays a stored response to a duplicate caller.
     *
     * @param stored what the original execution stored, or {@code null} when the record holds
     *               no HTTP response to replay
     */
    private void replay(StoredResponse stored, HttpServletResponse response) throws IOException {
        if (stored == null) {
            HttpIdempotencyMapper.replayEmpty(response);
            return;
        }
        HttpIdempotencyMapper.replay(stored, response);
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
