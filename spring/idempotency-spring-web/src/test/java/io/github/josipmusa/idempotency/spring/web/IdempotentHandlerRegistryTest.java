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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.github.josipmusa.idempotency.core.IdempotencyConfig;
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
    void When_InvalidTtl_Expect_ThrowsIllegalStateException() {
        setupHandler(AnnotationHelper.annotation(true, "2h", ""));

        assertThatThrownBy(() -> registry.afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Idempotent(ttl = \"2h\")")
                .hasMessageContaining("PT");
    }

    @Test
    void When_InvalidLease_Expect_ThrowsIllegalStateException() {
        setupHandler(AnnotationHelper.annotation(true, "", "10s"));

        assertThatThrownBy(() -> registry.afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Idempotent(lease = \"10s\")")
                .hasMessageContaining("PT");
    }

    @Test
    void When_InvalidWaitTimeout_Expect_ThrowsIllegalStateException() {
        setupHandler(AnnotationHelper.annotation(true, "", "", "3s"));

        assertThatThrownBy(() -> registry.afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Idempotent(waitTimeout = \"3s\")")
                .hasMessageContaining("PT");
    }

    @Test
    void When_ValidAnnotation_Expect_ResolvesCorrectDurations() {
        HandlerMethod handlerMethod = setupHandler(AnnotationHelper.annotation(true, "PT2H", "PT30S", "PT0S"));
        registry.afterSingletonsInstantiated();

        ResolvedIdempotent resolved = registry.resolve(handlerMethod);

        assertThat(resolved).isNotNull();
        assertThat(resolved.ttl()).isEqualTo(Duration.ofHours(2));
        assertThat(resolved.lease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(resolved.waitTimeout()).isZero();
    }

    @Test
    void When_LeaseAndWaitDiffer_Expect_BothResolvedIndependently() {
        HandlerMethod handlerMethod = setupHandler(AnnotationHelper.annotation(true, "", "PT5M", "PT2S"));
        registry.afterSingletonsInstantiated();

        ResolvedIdempotent resolved = registry.resolve(handlerMethod);

        assertThat(resolved.lease()).isEqualTo(Duration.ofMinutes(5));
        assertThat(resolved.waitTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void When_EmptyDurations_Expect_FallBackToConfigDefaults() {
        HandlerMethod handlerMethod = setupHandler(AnnotationHelper.annotation(true, "", ""));
        registry.afterSingletonsInstantiated();

        ResolvedIdempotent resolved = registry.resolve(handlerMethod);

        assertThat(resolved.ttl()).isEqualTo(IdempotencyConfig.defaults().defaultTtl());
        assertThat(resolved.lease()).isEqualTo(IdempotencyConfig.defaults().defaultLeaseDuration());
        assertThat(resolved.waitTimeout())
                .isEqualTo(IdempotencyConfig.defaults().defaultWaitTimeout());
    }

    @Test
    void When_NonAnnotatedHandler_Expect_NotRegistered() {
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethodAnnotation(Idempotent.class)).thenReturn(null);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mock(RequestMappingInfo.class), handlerMethod));
        Method mockedMethod = mock(Method.class);
        when(handlerMethod.getMethod()).thenReturn(mockedMethod);

        registry.afterSingletonsInstantiated();

        assertThat(registry.resolve(handlerMethod)).isNull();
    }

    @Test
    void When_Resolved_Expect_ScopeIsSimpleClassNameAndMethodName() {
        HandlerMethod handlerMethod =
                setupHandler(AnnotationHelper.annotation(true), PaymentController.class, "create");
        registry.afterSingletonsInstantiated();

        ResolvedIdempotent resolved = registry.resolve(handlerMethod);

        assertThat(resolved.scope()).isEqualTo("PaymentController.create");
    }

    @Test
    void When_ScopeExceedsMaxLength_Expect_ThrowsIllegalStateException() {
        setupHandler(AnnotationHelper.annotation(true), PaymentController.class, "m".repeat(128));

        assertThatThrownBy(() -> registry.afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope")
                .hasMessageContaining("128");
    }

    private HandlerMethod setupHandler(Idempotent annotation) {
        return setupHandler(annotation, PaymentController.class, "create");
    }

    private HandlerMethod setupHandler(Idempotent annotation, Class<?> beanType, String methodName) {
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethodAnnotation(Idempotent.class)).thenReturn(annotation);
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mock(RequestMappingInfo.class), handlerMethod));
        Method method = mock(Method.class);
        when(method.getName()).thenReturn(methodName);
        when(handlerMethod.getMethod()).thenReturn(method);
        doReturn(beanType).when(handlerMethod).getBeanType();
        return handlerMethod;
    }

    static class PaymentController {}
}
