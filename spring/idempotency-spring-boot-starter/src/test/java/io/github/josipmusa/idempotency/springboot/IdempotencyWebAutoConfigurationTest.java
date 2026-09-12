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
package io.github.josipmusa.idempotency.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.spring.web.IdempotencyFilter;
import io.github.josipmusa.idempotency.spring.web.ResponseSanitizer;
import io.github.josipmusa.idempotency.spring.web.WebIdempotencyConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** The HTTP autoconfiguration: active only in a Servlet web application with Spring MVC. */
class IdempotencyWebAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(IdempotencyAutoConfiguration.class, IdempotencyWebAutoConfiguration.class))
            .withBean(RequestMappingHandlerMapping.class, () -> mock(RequestMappingHandlerMapping.class));

    @Test
    void When_ServletWebApplicationWithStore_Expect_FilterCreatedAndRegistered() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotencyFilter.class);
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                });
    }

    @Test
    void When_NotAServletWebApplication_Expect_FilterBacksOff() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        IdempotencyAutoConfiguration.class, IdempotencyWebAutoConfiguration.class))
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IdempotencyFilter.class);
                    assertThat(context).doesNotHaveBean(WebIdempotencyConfig.class);
                });
    }

    @Test
    void When_SpringMvcAbsent_Expect_FilterBacksOff() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(RequestMappingHandlerMapping.class))
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> assertThat(context).doesNotHaveBean(IdempotencyFilter.class));
    }

    @Test
    void When_NoStoreBeanPresent_Expect_NoFilterCreated() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(IdempotencyFilter.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
        });
    }

    @Test
    void When_DefaultWebProperties_Expect_AppliedToWebConfig() {
        contextRunner.run(context -> {
            WebIdempotencyConfig config = context.getBean(WebIdempotencyConfig.class);
            assertThat(config.keyHeader()).isEqualTo("Idempotency-Key");
            assertThat(config.required()).isTrue();
            assertThat(config.inFlightStatus()).isEqualTo(409);
        });
    }

    @Test
    void When_CustomWebProperties_Expect_AppliedToWebConfig() {
        contextRunner
                .withPropertyValues(
                        "idempotency.web.key-header=X-Request-Id",
                        "idempotency.web.required=false",
                        "idempotency.web.in-flight-status=429")
                .run(context -> {
                    WebIdempotencyConfig config = context.getBean(WebIdempotencyConfig.class);
                    assertThat(config.keyHeader()).isEqualTo("X-Request-Id");
                    assertThat(config.required()).isFalse();
                    assertThat(config.inFlightStatus()).isEqualTo(429);
                });
    }

    @Test
    void When_DefaultFilterOrder_Expect_AppliedToRegistration() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> assertThat(
                                context.getBean(FilterRegistrationBean.class).getOrder())
                        .isZero());
    }

    @Test
    void When_CustomFilterOrder_Expect_AppliedToRegistration() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withPropertyValues("idempotency.web.filter-order=10")
                .run(context -> assertThat(
                                context.getBean(FilterRegistrationBean.class).getOrder())
                        .isEqualTo(10));
    }

    @Test
    void When_CustomWebConfigBeanPresent_Expect_AutoConfiguredWebConfigSkipped() {
        WebIdempotencyConfig custom = WebIdempotencyConfig.withKeyHeader("X-Custom-Key");
        contextRunner.withBean(WebIdempotencyConfig.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(WebIdempotencyConfig.class);
            assertThat(context.getBean(WebIdempotencyConfig.class)).isSameAs(custom);
        });
    }

    @Test
    void When_NoCustomSanitizerBean_Expect_DefaultSanitizerRegistered() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ResponseSanitizer.class));
    }

    @Test
    void When_CustomSanitizerBean_Expect_AutoConfiguredSanitizerSkipped() {
        ResponseSanitizer custom = response -> response;
        contextRunner.withBean(ResponseSanitizer.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(ResponseSanitizer.class);
            assertThat(context.getBean(ResponseSanitizer.class)).isSameAs(custom);
        });
    }

    @Test
    void When_CustomFilterBeanPresent_Expect_AutoConfiguredFilterSkipped() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withBean(IdempotencyFilter.class, () -> mock(IdempotencyFilter.class))
                .run(context -> assertThat(context).hasSingleBean(IdempotencyFilter.class));
    }

    @Test
    void When_CustomMaxBodyBytes_Expect_FilterStillCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withPropertyValues("idempotency.web.max-body-bytes=2097152")
                .run(context -> assertThat(context).hasSingleBean(IdempotencyFilter.class));
    }
}
