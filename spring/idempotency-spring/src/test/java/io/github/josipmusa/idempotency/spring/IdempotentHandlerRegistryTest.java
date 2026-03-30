package io.github.josipmusa.idempotency.spring;

import static io.github.josipmusa.idempotency.spring.IdempotentHandlerRegistry.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.github.josipmusa.core.IdempotencyConfig;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class IdempotentHandlerRegistryTest {

    private RequestMappingHandlerMapping handlerMapping;
    private IdempotentHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        handlerMapping = mock(RequestMappingHandlerMapping.class);
        registry = new IdempotentHandlerRegistry(handlerMapping, IdempotencyConfig.defaults());
    }

    @Test
    void invalidTtl_throwsIllegalStateException() {
        setupHandler(AnnotationHelper.annotation(true, "2h", ""));

        assertThatThrownBy(() -> registry.afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Idempotent(ttl = \"2h\")")
                .hasMessageContaining("PT");
    }

    @Test
    void invalidLockTimeout_throwsIllegalStateException() {
        setupHandler(AnnotationHelper.annotation(true, "", "10s"));

        assertThatThrownBy(() -> registry.afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Idempotent(lockTimeout = \"10s\")")
                .hasMessageContaining("PT");
    }

    @Test
    void validAnnotation_resolvesCorrectDurations() {
        HandlerMethod handlerMethod = setupHandler(AnnotationHelper.annotation(true, "PT2H", "PT30S"));
        registry.afterSingletonsInstantiated();

        ResolvedIdempotent resolved = registry.resolve(handlerMethod);

        assertThat(resolved).isNotNull();
        assertThat(resolved.ttl()).isEqualTo(Duration.ofHours(2));
        assertThat(resolved.lockTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void emptyDurations_fallBackToConfigDefaults() {
        HandlerMethod handlerMethod = setupHandler(AnnotationHelper.annotation(true, "", ""));
        registry.afterSingletonsInstantiated();

        ResolvedIdempotent resolved = registry.resolve(handlerMethod);

        assertThat(resolved.ttl()).isEqualTo(IdempotencyConfig.defaults().defaultTtl());
        assertThat(resolved.lockTimeout())
                .isEqualTo(IdempotencyConfig.defaults().defaultLockTimeout());
    }

    @Test
    void nonAnnotatedHandler_notRegistered() {
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethodAnnotation(Idempotent.class)).thenReturn(null);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mock(RequestMappingInfo.class), handlerMethod));
        when(handlerMethod.getMethod()).thenReturn(mock(Method.class));

        registry.afterSingletonsInstantiated();

        assertThat(registry.resolve(handlerMethod)).isNull();
    }

    private HandlerMethod setupHandler(Idempotent annotation) {
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethodAnnotation(Idempotent.class)).thenReturn(annotation);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mock(RequestMappingInfo.class), handlerMethod));
        when(handlerMethod.getMethod()).thenReturn(mock(Method.class));
        return handlerMethod;
    }
}
