package io.github.josipmusa.idempotency.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.github.josipmusa.core.*;
import io.github.josipmusa.core.exception.IdempotencyLockTimeoutException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private IdempotencyStore store;
    private RequestMappingHandlerMapping handlerMapping;
    private IdempotentHandlerRegistry registry;
    private IdempotencyFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        engine = mock(IdempotencyEngine.class);
        store = mock(IdempotencyStore.class);
        handlerMapping = mock(RequestMappingHandlerMapping.class);
        IdempotencyConfig idempotencyConfig = IdempotencyConfig.defaults();
        registry = new IdempotentHandlerRegistry(handlerMapping, idempotencyConfig);
        filter = new IdempotencyFilter(engine, store, idempotencyConfig, handlerMapping, registry);

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
    }

    @Test
    void When_NonAnnotatedHandler_Expect_ProceedsNormally() throws Exception {
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethodAnnotation(Idempotent.class)).thenReturn(null);
        HandlerExecutionChain chain = new HandlerExecutionChain(handlerMethod);
        when(handlerMapping.getHandler(request)).thenReturn(chain);
        when(handlerMethod.getMethod()).thenReturn(mock(Method.class));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(engine);
    }

    @Test
    void When_MissingKeyAndRequired_Expect_Returns422() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation(true));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\": \"Idempotency-Key header is required\"}");
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        verifyNoInteractions(engine);
    }

    @Test
    void When_MissingKeyAndNotRequired_Expect_ProceedsNormally() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation(false));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(engine);
    }

    @Test
    void When_ExecutedResult_Expect_StoresResponseAndCopiesBody() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation(true));
        request.addHeader("Idempotency-Key", "test-key");

        doAnswer(invocation -> {
                    ThrowingRunnable action = invocation.getArgument(1);
                    action.run();
                    return ExecutionResult.executed();
                })
                .when(engine)
                .execute(any(), any());

        doAnswer(invocation -> {
                    HttpServletResponse resp = invocation.getArgument(1);
                    resp.setStatus(201);
                    resp.setContentType("application/json");
                    resp.getWriter().write("{\"id\":\"1\"}");
                    return null;
                })
                .when(filterChain)
                .doFilter(any(), any());

        filter.doFilter(request, response, filterChain);

        verify(store).complete(eq("test-key"), any(StoredResponse.class), any(Duration.class));
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"1\"}");
        assertThat(response.getStatus()).isEqualTo(201);
    }

    @Test
    void When_AnnotationTtlOverride_Expect_CustomTtlPassedToStore() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation(true, "PT2H", ""));
        request.addHeader("Idempotency-Key", "test-key");

        doAnswer(invocation -> {
                    ThrowingRunnable action = invocation.getArgument(1);
                    action.run();
                    return ExecutionResult.executed();
                })
                .when(engine)
                .execute(any(), any());

        filter.doFilter(request, response, filterChain);

        verify(store).complete(eq("test-key"), any(StoredResponse.class), eq(Duration.ofHours(2)));
    }

    @Test
    void When_DuplicateResult_Expect_StoredResponseReplayed() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation(true));
        request.addHeader("Idempotency-Key", "test-key");
        StoredResponse stored = storedResponse();
        when(engine.execute(any(), any())).thenReturn(ExecutionResult.duplicate(stored));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo("{\"id\":\"1\"}".getBytes());
    }

    @Test
    void When_DuplicateResult_Expect_ReplayedHeaderSet() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation(true));
        request.addHeader("Idempotency-Key", "test-key");
        when(engine.execute(any(), any())).thenReturn(ExecutionResult.duplicate(storedResponse()));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void When_LockTimeoutException_Expect_Returns503() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation(true));
        request.addHeader("Idempotency-Key", "test-key");
        when(engine.execute(any(), any()))
                .thenThrow(new IdempotencyLockTimeoutException("test-key", Duration.ofSeconds(10)));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\": \"Request with this key is already being processed\"}");
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
    }

    @Test
    void When_ActionThrows_Expect_ReleaseCalledByEngine() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation(true));
        request.addHeader("Idempotency-Key", "test-key");
        RuntimeException actionException = new RuntimeException("action failed");
        when(engine.execute(any(), any())).thenThrow(actionException);

        assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
                .isSameAs(actionException);

        verify(store, never()).release(any());
    }

    @Test
    void When_StoreCompleteThrows_Expect_BodyStillWritten() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation(true));
        request.addHeader("Idempotency-Key", "test-key");

        doAnswer(invocation -> {
                    ThrowingRunnable action = invocation.getArgument(1);
                    action.run();
                    return ExecutionResult.executed();
                })
                .when(engine)
                .execute(any(), any());

        doAnswer(invocation -> {
                    HttpServletResponse resp = invocation.getArgument(1);
                    resp.setStatus(200);
                    resp.setContentType("application/json");
                    resp.getWriter().write("{\"id\":\"1\"}");
                    return null;
                })
                .when(filterChain)
                .doFilter(any(), any());

        doThrow(new RuntimeException("store unavailable")).when(store).complete(any(), any(), any());

        filter.doFilter(request, response, filterChain);

        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"1\"}");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void When_StoreCompleteThrowsAndRetryBeforeLockExpiry_Expect_Returns503() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation(true));
        request.addHeader("Idempotency-Key", "test-key");

        doAnswer(invocation -> {
                    ThrowingRunnable action = invocation.getArgument(1);
                    action.run();
                    return ExecutionResult.executed();
                })
                .when(engine)
                .execute(any(), any());

        doAnswer(invocation -> {
                    HttpServletResponse resp = invocation.getArgument(1);
                    resp.setStatus(200);
                    resp.getWriter().write("{}");
                    return null;
                })
                .when(filterChain)
                .doFilter(any(), any());

        doThrow(new RuntimeException("store down")).when(store).complete(any(), any(), any());

        filter.doFilter(request, response, filterChain);
        assertThat(response.getStatus()).isEqualTo(200);

        // Key is still IN_PROGRESS because complete failed — retry sees lock timeout
        MockHttpServletResponse response2 = new MockHttpServletResponse();
        doThrow(new IdempotencyLockTimeoutException("test-key", Duration.ofSeconds(10)))
                .when(engine)
                .execute(any(), any());

        filter.doFilter(request, response2, filterChain);

        assertThat(response2.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(response2.getContentAsString())
                .isEqualTo("{\"error\": \"Request with this key is already being processed\"}");
    }

    @Test
    void When_KeyTooLong_Expect_Returns422() throws Exception {
        setupAnnotatedHandler(AnnotationHelper.annotation(true));
        request.addHeader("Idempotency-Key", "k".repeat(256));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\": \"Idempotency-Key must not exceed 255 characters\"}");
        verifyNoInteractions(engine);
    }

    private void setupAnnotatedHandler(Idempotent annotation) throws Exception {
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethodAnnotation(Idempotent.class)).thenReturn(annotation);
        HandlerExecutionChain chain = new HandlerExecutionChain(handlerMethod);

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mock(RequestMappingInfo.class), handlerMethod));
        when(handlerMethod.getMethod()).thenReturn(mock(Method.class));
        registry.afterSingletonsInstantiated();
        when(handlerMapping.getHandler(request)).thenReturn(chain);
    }

    private StoredResponse storedResponse() {
        return new StoredResponse(
                200, Map.of("Content-Type", List.of("application/json")), "{\"id\":\"1\"}".getBytes(), Instant.now());
    }
}
