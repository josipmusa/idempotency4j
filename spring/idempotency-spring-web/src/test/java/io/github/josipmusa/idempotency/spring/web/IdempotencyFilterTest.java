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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import io.github.josipmusa.idempotency.core.*;
import io.github.josipmusa.idempotency.core.exception.IdempotencyFingerprintMismatchException;
import io.github.josipmusa.idempotency.spring.Idempotent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class IdempotencyFilterTest {

    private IdempotencyEngine engine;
    private RequestMappingHandlerMapping handlerMapping;
    private IdempotentHandlerRegistry registry;
    private IdempotencyFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    /** What the codec the filter handed the engine turned the captured response into. */
    private final AtomicReference<Payload> encoded = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        engine = mock(IdempotencyEngine.class);
        handlerMapping = mock(RequestMappingHandlerMapping.class);
        IdempotencyConfig idempotencyConfig = IdempotencyConfig.defaults();
        registry = new IdempotentHandlerRegistry(handlerMapping, idempotencyConfig);
        filter = new IdempotencyFilter(engine, WebIdempotencyConfig.defaults(), handlerMapping, registry);

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
        encoded.set(null);
    }

    @Test
    void When_NonAnnotatedHandler_Expect_ProceedsNormally() throws Exception {
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethodAnnotation(Idempotent.class)).thenReturn(null);
        HandlerExecutionChain chain = new HandlerExecutionChain(handlerMethod);
        when(handlerMapping.getHandler(request)).thenReturn(chain);
        Method mockedMethod = mock(Method.class);
        when(handlerMethod.getMethod()).thenReturn(mockedMethod);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(engine);
    }

    @Test
    void When_NonAnnotatedHandlerWithIdempotencyKey_Expect_ProceedsNormally() throws Exception {
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethodAnnotation(Idempotent.class)).thenReturn(null);
        HandlerExecutionChain chain = new HandlerExecutionChain(handlerMethod);
        when(handlerMapping.getHandler(request)).thenReturn(chain);
        Method mockedMethod = mock(Method.class);
        when(handlerMethod.getMethod()).thenReturn(mockedMethod);
        request.addHeader("Idempotency-Key", "test-key");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(engine);
    }

    @Test
    void When_MissingKeyAndRequired_Expect_Returns422() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\": \"Idempotency-Key header is required\"}");
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        verifyNoInteractions(engine);
    }

    @Test
    void When_RequiredFalse_Expect_UnkeyedRequestPassesThrough() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());

        filterWithRequired(false).doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(engine);
    }

    @Test
    void When_BlankKeyAndRequired_Expect_Returns422() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "   ");

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\": \"Idempotency-Key header is required\"}");
        verifyNoInteractions(engine);
    }

    @Test
    void When_RequiredFalseAndKeyIsBlank_Expect_RequestPassesThrough() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "   ");

        filterWithRequired(false).doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(engine);
    }

    @Test
    void When_Executed_Expect_CapturedResponseEncodedAndBodyCopied() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        stubExecuted();
        handlerWrites(201, "application/json", "{\"id\":\"1\"}");

        filter.doFilter(request, response, filterChain);

        assertThat(decodeEncoded().statusCode()).isEqualTo(201);
        assertThat(new String(decodeEncoded().body())).isEqualTo("{\"id\":\"1\"}");
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"1\"}");
        assertThat(response.getStatus()).isEqualTo(201);
    }

    @Test
    void When_AnnotationTtlOverride_Expect_CustomTtlOnContext() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation("PT2H", ""));
        request.addHeader("Idempotency-Key", "test-key");
        stubExecuted();

        filter.doFilter(request, response, filterChain);

        verify(engine).execute(argThat(context -> context.ttl().equals(Duration.ofHours(2))), any(), any());
    }

    @Test
    void When_Replayed_Expect_StoredResponseWritten() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        stubReplayed(storedResponse());

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo("{\"id\":\"1\"}".getBytes());
    }

    @Test
    void When_Replayed_Expect_ReplayHeaderPresent() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        stubReplayed(storedResponse());

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void When_Replayed_Expect_ContentLengthSet() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        byte[] body = "hello".getBytes();
        stubReplayed(new StoredResponse(200, Map.of("Content-Type", List.of("application/json")), body));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getContentLength()).isEqualTo(body.length);
    }

    @Test
    void When_ReplayedContainsTransportHeaders_Expect_NotReplayedAndLengthRecalculated() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        byte[] body = "hello".getBytes();
        stubReplayed(new StoredResponse(
                200,
                Map.of(
                        "Connection", List.of("keep-alive, X-Hop"),
                        "Content-Length", List.of("999"),
                        "Transfer-Encoding", List.of("chunked"),
                        "Keep-Alive", List.of("timeout=5"),
                        "X-Hop", List.of("not-end-to-end"),
                        "X-End-To-End", List.of("kept")),
                body));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Connection")).isNull();
        assertThat(response.getHeader("Transfer-Encoding")).isNull();
        assertThat(response.getHeader("Keep-Alive")).isNull();
        assertThat(response.getHeader("X-Hop")).isNull();
        assertThat(response.getContentLength()).isEqualTo(body.length);
        assertThat(response.getHeader("X-End-To-End")).isEqualTo("kept");
    }

    @Test
    void When_InFlight_Expect_409WithRetryAfter() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        when(engine.execute(any(), any(), any())).thenReturn(new Outcome.InFlight<>(Duration.ofMillis(2400)));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\": \"Request with this key is already being processed\"}");
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void When_InFlightWithNoRemainingLease_Expect_RetryAfterAtLeastOneSecond() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        when(engine.execute(any(), any(), any())).thenReturn(new Outcome.InFlight<>(Duration.ZERO));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void When_InFlightStatusConfigured_Expect_ConfiguredStatusUsed() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        IdempotencyFilter configured = new IdempotencyFilter(
                engine, WebIdempotencyConfig.builder().inFlightStatus(503).build(), handlerMapping, registry);
        when(engine.execute(any(), any(), any())).thenReturn(new Outcome.InFlight<>(Duration.ofSeconds(5)));

        configured.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void When_ActionThrows_Expect_ExceptionPropagates() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        RuntimeException actionException = new RuntimeException("action failed");
        when(engine.execute(any(), any(), any())).thenThrow(actionException);

        assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
                .isSameAs(actionException);
    }

    /**
     * The engine is configured with {@code LOG_AND_RETURN} by the starter, so a completion the
     * store refused still comes back as {@code Executed} and the handler's response reaches the
     * client.
     */
    @Test
    void When_CompletionFailedButOutcomeExecuted_Expect_BodyStillWritten() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        stubExecuted();
        handlerWrites(200, "application/json", "{\"id\":\"1\"}");

        filter.doFilter(request, response, filterChain);

        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"1\"}");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void When_CompletionFailedAndRetriedBeforeLeaseExpiry_Expect_InFlightStatus() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        stubExecuted();
        handlerWrites(200, null, "{}");

        filter.doFilter(request, response, filterChain);
        assertThat(response.getStatus()).isEqualTo(200);

        // The storage outcome is indeterminate. This retry models the backend reporting that the
        // key is still in flight, because the failed attempt's lease has not expired yet.
        MockHttpServletResponse retry = new MockHttpServletResponse();
        doReturn(new Outcome.InFlight<>(Duration.ofSeconds(10))).when(engine).execute(any(), any(), any());

        filter.doFilter(request, retry, filterChain);

        assertThat(retry.getStatus()).isEqualTo(409);
        assertThat(retry.getContentAsString())
                .isEqualTo("{\"error\": \"Request with this key is already being processed\"}");
    }

    @Test
    void When_KeyTooLong_Expect_Returns422() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "k".repeat(256));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\": \"Idempotency-Key must not exceed 255 characters\"}");
        verifyNoInteractions(engine);
    }

    @Test
    void When_RequestBodyExceedsLimit_Expect_Returns413() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "key-123");
        request.setContent("this body is definitely longer than ten bytes".getBytes());

        IdempotencyFilter limitedFilter =
                new IdempotencyFilter(engine, WebIdempotencyConfig.defaults(), handlerMapping, registry, 10);

        limitedFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("Request body exceeds maximum allowed size");
        verifyNoInteractions(engine);
    }

    @Test
    void When_Replayed_Expect_CacheControlNoStoreSet() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        stubReplayed(storedResponse());

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void When_RequestBodyExactlyAtLimit_Expect_ProceedsNormally() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "key-123");
        request.setContent("0123456789".getBytes()); // exactly 10 bytes
        IdempotencyFilter limitedFilter =
                new IdempotencyFilter(engine, WebIdempotencyConfig.defaults(), handlerMapping, registry, 10);
        stubExecuted();

        limitedFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isNotEqualTo(413);
        verify(engine).execute(any(), any(), any());
    }

    @Test
    void When_FingerprintMismatch_Expect_Returns422() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{\"amount\":100}".getBytes());
        when(engine.execute(any(), any(), any()))
                .thenThrow(new IdempotencyFingerprintMismatchException(
                        new IdempotencyIdentity("PaymentController.create", "key-1"), "stored-hash", "received-hash"));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).contains("Idempotency-Key reused with a different request body");
    }

    @Test
    void When_SameBodyResubmitted_Expect_Replayed() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{\"amount\":100}".getBytes());
        stubReplayed(new StoredResponse(
                200, Map.of("Content-Type", List.of("application/json")), "{\"id\":\"123\"}".getBytes()));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void When_SanitizerConfigured_Expect_SanitizedResponseStoredNotOriginal() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");

        ResponseSanitizer sanitizer = storedResponse -> new StoredResponse(
                storedResponse.statusCode(),
                storedResponse.headers().entrySet().stream()
                        .filter(e -> !e.getKey().equalsIgnoreCase("X-Secret"))
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)),
                storedResponse.body());

        IdempotencyFilter filterWithSanitizer = new IdempotencyFilter(
                engine, WebIdempotencyConfig.defaults(), handlerMapping, registry, -1L, sanitizer);
        stubExecuted();
        doAnswer(invocation -> {
                    HttpServletResponse resp = invocation.getArgument(1);
                    resp.setStatus(200);
                    resp.setContentType("application/json");
                    resp.addHeader("X-Secret", "token-abc");
                    resp.getWriter().write("{\"id\":\"1\"}");
                    return null;
                })
                .when(filterChain)
                .doFilter(any(), any());

        filterWithSanitizer.doFilter(request, response, filterChain);

        assertThat(decodeEncoded().headers()).doesNotContainKey("X-Secret");
    }

    @Test
    void When_SanitizerThrows_Expect_OriginalResponseStillWrittenAndNotStored() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        IdempotencyFilter filterWithFailingSanitizer = new IdempotencyFilter(
                engine, WebIdempotencyConfig.defaults(), handlerMapping, registry, -1L, ignored -> {
                    throw new IllegalStateException("sanitizer failed");
                });
        stubExecutedToleratingEncodingFailure();
        handlerWrites(201, null, "created");

        filterWithFailingSanitizer.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("created");
        assertThat(encoded).hasValue(null);
    }

    @Test
    void When_ResponseContainsTransportHeaders_Expect_NotStored() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        stubExecuted();
        doAnswer(invocation -> {
                    HttpServletResponse resp = invocation.getArgument(1);
                    resp.setHeader("Connection", "keep-alive");
                    resp.setHeader("Transfer-Encoding", "chunked");
                    resp.setHeader("X-End-To-End", "kept");
                    resp.getWriter().write("ok");
                    return null;
                })
                .when(filterChain)
                .doFilter(any(), any());

        filter.doFilter(request, response, filterChain);

        assertThat(decodeEncoded().headers())
                .containsEntry("X-End-To-End", List.of("kept"))
                .doesNotContainKeys("Connection", "Transfer-Encoding", "Content-Length");
    }

    @Test
    void When_AnnotatedHandler_Expect_ScopeIsHandlerClassAndMethod() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        stubReplayed(storedResponse());

        filter.doFilter(request, response, filterChain);

        verify(engine)
                .execute(
                        argThat(context -> context.identity()
                                .equals(new IdempotencyIdentity("PaymentController.create", "test-key"))),
                        any(),
                        any());
    }

    @Test
    void When_ReplayedCarriesNonHttpPayload_Expect_NoContentMarkedAsReplayed() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation());
        request.addHeader("Idempotency-Key", "test-key");
        stubReplayedFrom(Payload.none());

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(response.getContentAsByteArray()).isEmpty();
        verify(filterChain, never()).doFilter(any(), any());
    }

    private IdempotencyFilter filterWithRequired(boolean required) {
        return new IdempotencyFilter(
                engine, WebIdempotencyConfig.builder().required(required).build(), handlerMapping, registry);
    }

    private void setupAnnotatedHandler(Idempotent annotation) throws Exception {
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethodAnnotation(Idempotent.class)).thenReturn(annotation);
        HandlerExecutionChain chain = new HandlerExecutionChain(handlerMethod);

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mock(RequestMappingInfo.class), handlerMethod));
        Method method = mock(Method.class);
        when(method.getName()).thenReturn("create");
        when(handlerMethod.getMethod()).thenReturn(method);
        doReturn(PaymentController.class).when(handlerMethod).getBeanType();
        registry.afterSingletonsInstantiated();
        when(handlerMapping.getHandler(request)).thenReturn(chain);
    }

    static class PaymentController {}

    /**
     * Stands in for an engine that acquired the lease: it runs the supplier, encodes the captured
     * response with the codec the filter supplied, and reports {@code Executed}.
     */
    @SuppressWarnings("unchecked")
    private void stubExecuted() throws Exception {
        doAnswer(invocation -> {
                    ThrowingSupplier<StoredResponse> action = invocation.getArgument(1);
                    PayloadCodec<StoredResponse> codec = invocation.getArgument(2);
                    StoredResponse captured = action.get();
                    encoded.set(codec.encode(captured));
                    return new Outcome.Executed<>(captured);
                })
                .when(engine)
                .execute(any(), any(), any());
    }

    /** The same, under {@code LOG_AND_RETURN}: an encoding failure is logged and swallowed. */
    @SuppressWarnings("unchecked")
    private void stubExecutedToleratingEncodingFailure() throws Exception {
        doAnswer(invocation -> {
                    ThrowingSupplier<StoredResponse> action = invocation.getArgument(1);
                    PayloadCodec<StoredResponse> codec = invocation.getArgument(2);
                    StoredResponse captured = action.get();
                    try {
                        encoded.set(codec.encode(captured));
                    } catch (RuntimeException encodingFailure) {
                        // the engine logs and returns Executed anyway
                    }
                    return new Outcome.Executed<>(captured);
                })
                .when(engine)
                .execute(any(), any(), any());
    }

    private void stubReplayed(StoredResponse stored) throws Exception {
        stubReplayedFrom(new StoredResponseCodec().encode(stored));
    }

    /** Replays through the filter's own codec, so payload decoding is part of what is tested. */
    @SuppressWarnings("unchecked")
    private void stubReplayedFrom(Payload stored) throws Exception {
        doAnswer(invocation -> {
                    PayloadCodec<StoredResponse> codec = invocation.getArgument(2);
                    return new Outcome.Replayed<>(codec.decode(stored), Instant.now());
                })
                .when(engine)
                .execute(any(), any(), any());
    }

    private void handlerWrites(int status, String contentType, String body) throws Exception {
        doAnswer(invocation -> {
                    HttpServletResponse resp = invocation.getArgument(1);
                    resp.setStatus(status);
                    if (contentType != null) {
                        resp.setContentType(contentType);
                    }
                    resp.getWriter().write(body);
                    return null;
                })
                .when(filterChain)
                .doFilter(any(), any());
    }

    private StoredResponse storedResponse() {
        return new StoredResponse(
                200, Map.of("Content-Type", List.of("application/json")), "{\"id\":\"1\"}".getBytes());
    }

    /** The response the filter actually handed the store, decoded back out of the payload. */
    private StoredResponse decodeEncoded() {
        return new StoredResponseCodec().decode(encoded.get());
    }
}
