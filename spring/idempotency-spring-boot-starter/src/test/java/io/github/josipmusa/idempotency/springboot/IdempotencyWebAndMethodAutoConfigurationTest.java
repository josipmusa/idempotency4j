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
import io.github.josipmusa.idempotency.spring.Idempotent;
import io.github.josipmusa.idempotency.spring.web.IdempotencyFilter;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The combination every Servlet application actually gets: the HTTP filter and the method
 * advisor wired at the same time.
 *
 * <p>Each autoconfiguration used to be tested only on its own, which hid the fact that the
 * advisor claimed HTTP handlers too and then failed the context over the {@code key} such a
 * handler has no use for. An {@code @Idempotent} endpoint is the filter's, and only the
 * filter's.
 */
class IdempotencyWebAndMethodAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class,
                    IdempotencyAutoConfiguration.class,
                    IdempotencyWebAutoConfiguration.class,
                    IdempotencyMethodAutoConfiguration.class))
            .withBean(RequestMappingHandlerMapping.class, () -> mock(RequestMappingHandlerMapping.class))
            .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class));

    @Test
    void When_KeylessIdempotentOnHttpHandler_Expect_ContextStarts() {
        contextRunner.withUserConfiguration(ControllerConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotencyFilter.class);
        });
    }

    @Test
    void When_KeylessIdempotentOnHttpHandler_Expect_NotAdvisedByTheMethodInterceptor() {
        contextRunner.withUserConfiguration(ControllerConfiguration.class).run(context -> {
            PaymentController controller = context.getBean(PaymentController.class);

            // Advising it would guard the same call twice, under two different keys.
            assertThat(AopUtils.isAopProxy(controller)).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ControllerConfiguration {

        @Bean
        PaymentController paymentController() {
            return new PaymentController();
        }
    }

    @RestController
    static class PaymentController {

        @PostMapping("/payments")
        @Idempotent
        String create() {
            return "created";
        }
    }
}
