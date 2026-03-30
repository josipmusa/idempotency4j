package io.github.josipmusa.idempotency.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.github.josipmusa.core.AcquireResult;
import io.github.josipmusa.core.ExecutionResult;
import io.github.josipmusa.core.IdempotencyConfig;
import io.github.josipmusa.core.IdempotencyEngine;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.core.StoredResponse;
import io.github.josipmusa.core.ThrowingRunnable;
import io.github.josipmusa.core.exception.IdempotencyLockTimeoutException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.annotation.Annotation;
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
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class IdempotencyFilterTest {

    private IdempotencyEngine engine;
    private IdempotencyStore store;
    private IdempotencyConfig config;
    private RequestMappingHandlerMapping handlerMapping;
    private IdempotencyFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() throws Exception {
        engine = mock(IdempotencyEngine.class);
        store = mock(IdempotencyStore.class);
        config = IdempotencyConfig.defaults();
        handlerMapping = mock(RequestMappingHandlerMapping.class);
        filter = new IdempotencyFilter(engine, store, config, handlerMapping);

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
    }

    // ---- helpers ----

    private void setupAnnotatedHandler(Idempotent annotation) throws Exception {
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethodAnnotation(Idempotent.class)).thenReturn(annotation);
        HandlerExecutionChain chain = new HandlerExecutionChain(handlerMethod);
        when(handlerMapping.getHandler(request)).thenReturn(chain);
    }

    private Idempotent annotation(boolean required) {
        return new Idempotent() {
            @Override
            public String ttl() {
                return "";
            }

            @Override
            public String lockTimeout() {
                return "";
            }

            @Override
            public boolean required() {
                return required;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return Idempotent.class;
            }
        };
    }

    private StoredResponse storedResponse() {
        return new StoredResponse(
                200,
                Map.of("Content-Type", List.of("application/json")),
                "{\"id\":\"1\"}".getBytes(),
                Instant.now());
    }

    // ---- tests ----

    @Test
    void nonAnnotatedHandler_proceedsNormally() throws Exception {
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethodAnnotation(Idempotent.class)).thenReturn(null);
        HandlerExecutionChain chain = new HandlerExecutionChain(handlerMethod);
        when(handlerMapping.getHandler(request)).thenReturn(chain);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(engine);
    }

    @Test
    void missingKeyAndRequired_returns400() throws Exception {
        setupAnnotatedHandler(annotation(true));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\": \"Idempotency-Key header is required\"}");
        assertThat(response.getContentType()).isEqualTo("application/json");
        verifyNoInteractions(engine);
    }

    @Test
    void missingKeyAndNotRequired_proceedsNormally() throws Exception {
        setupAnnotatedHandler(annotation(false));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(engine);
    }

    @Test
    void executedResult_storesResponseAndCopiesBody() throws Exception {
        setupAnnotatedHandler(annotation(true));
        request.addHeader("Idempotency-Key", "test-key");

        doAnswer(invocation -> {
                    ThrowingRunnable action = invocation.getArgument(1);
                    action.run();
                    return ExecutionResult.executed();
                })
                .when(engine)
                .execute(any(), any());

        doAnswer(invocation -> {
                    HttpServletResponse resp = (HttpServletResponse) invocation.getArgument(1);
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
    void duplicateResult_replaysStoredResponse() throws Exception {
        setupAnnotatedHandler(annotation(true));
        request.addHeader("Idempotency-Key", "test-key");
        StoredResponse stored = storedResponse();
        when(engine.execute(any(), any())).thenReturn(ExecutionResult.duplicate(stored));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo("{\"id\":\"1\"}".getBytes());
    }

    @Test
    void duplicateResult_setsReplayedHeader() throws Exception {
        setupAnnotatedHandler(annotation(true));
        request.addHeader("Idempotency-Key", "test-key");
        when(engine.execute(any(), any())).thenReturn(ExecutionResult.duplicate(storedResponse()));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("X-Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void lockTimeoutException_returns503() throws Exception {
        setupAnnotatedHandler(annotation(true));
        request.addHeader("Idempotency-Key", "test-key");
        when(engine.execute(any(), any()))
                .thenThrow(new IdempotencyLockTimeoutException("test-key", Duration.ofSeconds(10)));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\": \"Request with this key is already being processed\"}");
        assertThat(response.getContentType()).isEqualTo("application/json");
    }

    @Test
    void actionThrows_releaseIsCalledByEngine() throws Exception {
        setupAnnotatedHandler(annotation(true));
        request.addHeader("Idempotency-Key", "test-key");
        RuntimeException actionException = new RuntimeException("action failed");
        when(engine.execute(any(), any())).thenThrow(actionException);

        assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
                .isSameAs(actionException);

        verify(store, never()).release(any());
    }
}
