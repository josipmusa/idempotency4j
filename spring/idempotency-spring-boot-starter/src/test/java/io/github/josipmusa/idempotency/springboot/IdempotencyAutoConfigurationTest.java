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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.CompletionFailurePolicy;
import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyEngine;
import io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.spring.web.IdempotencyFilter;
import io.github.josipmusa.idempotency.spring.web.ResponseSanitizer;
import io.github.josipmusa.idempotency.spring.web.WebIdempotencyConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class IdempotencyAutoConfigurationTest {

    private static final List<String> listenerCalls = new CopyOnWriteArrayList<>();

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
            .withBean(RequestMappingHandlerMapping.class, () -> mock(RequestMappingHandlerMapping.class));

    @BeforeEach
    void resetListenerCalls() {
        listenerCalls.clear();
    }

    @Test
    void When_NoStoreBeanPresent_Expect_EngineAndFilterNotCreated() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(IdempotencyEngine.class);
            assertThat(context).doesNotHaveBean(IdempotencyFilter.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
        });
    }

    @Test
    void When_StoreBeanPresent_Expect_EngineAndFilterCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotencyEngine.class);
                    assertThat(context).hasSingleBean(IdempotencyFilter.class);
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                });
    }

    @Test
    void When_CustomEngineBeanPresent_Expect_AutoConfiguredEngineSkipped() {
        IdempotencyEngine customEngine = mock(IdempotencyEngine.class);
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withBean(IdempotencyEngine.class, () -> customEngine)
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotencyEngine.class);
                    assertThat(context.getBean(IdempotencyEngine.class)).isSameAs(customEngine);
                });
    }

    @Test
    void When_CustomConfigBeanPresent_Expect_AutoConfiguredConfigSkipped() {
        IdempotencyConfig customConfig =
                IdempotencyConfig.builder().defaultTtl(Duration.ofHours(3)).build();
        contextRunner.withBean(IdempotencyConfig.class, () -> customConfig).run(context -> {
            assertThat(context).hasSingleBean(IdempotencyConfig.class);
            assertThat(context.getBean(IdempotencyConfig.class)).isSameAs(customConfig);
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
    void When_DefaultProperties_Expect_AppliedToConfig() {
        contextRunner.run(context -> {
            IdempotencyConfig config = context.getBean(IdempotencyConfig.class);
            assertThat(config.defaultTtl()).isEqualTo(Duration.ofHours(24));
            assertThat(config.defaultLeaseDuration()).isEqualTo(Duration.ofSeconds(30));
            assertThat(config.defaultWaitTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(config.completionFailurePolicy()).isEqualTo(CompletionFailurePolicy.LOG_AND_RETURN);
            assertThat(context.getBean(WebIdempotencyConfig.class).keyHeader()).isEqualTo("Idempotency-Key");
        });
    }

    @Test
    void When_CustomProperties_Expect_AppliedToConfig() {
        contextRunner
                .withPropertyValues(
                        "idempotency.key-header=X-Request-Id",
                        "idempotency.default-ttl=PT2H",
                        "idempotency.default-lease=PT5M",
                        "idempotency.default-wait=PT0S",
                        "idempotency.completion-failure-policy=propagate")
                .run(context -> {
                    IdempotencyConfig config = context.getBean(IdempotencyConfig.class);
                    assertThat(config.defaultTtl()).isEqualTo(Duration.ofHours(2));
                    assertThat(config.defaultLeaseDuration()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(config.defaultWaitTimeout()).isZero();
                    assertThat(config.completionFailurePolicy()).isEqualTo(CompletionFailurePolicy.PROPAGATE);
                    assertThat(context.getBean(WebIdempotencyConfig.class).keyHeader())
                            .isEqualTo("X-Request-Id");
                });
    }

    @Test
    void When_DefaultFilterOrder_Expect_AppliedToRegistration() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getOrder()).isZero();
                });
    }

    @Test
    void When_CustomFilterOrder_Expect_AppliedToRegistration() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withPropertyValues("idempotency.filter-order=10")
                .run(context -> {
                    FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getOrder()).isEqualTo(10);
                });
    }

    @Test
    void When_CustomMaxBodyBytes_Expect_AppliedToFilter() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withPropertyValues("idempotency.max-body-bytes=2097152")
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotencyFilter.class);
                });
    }

    @Test
    void When_NonServletApplication_Expect_NoBeanCreated() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IdempotencyFilter.class);
                    assertThat(context).doesNotHaveBean(IdempotencyConfig.class);
                    assertThat(context).doesNotHaveBean(WebIdempotencyConfig.class);
                });
    }

    @Test
    void When_CustomWebConfigBeanPresent_Expect_AutoConfiguredWebConfigSkipped() {
        WebIdempotencyConfig customWebConfig = WebIdempotencyConfig.withKeyHeader("X-Custom-Key");
        contextRunner
                .withBean(WebIdempotencyConfig.class, () -> customWebConfig)
                .run(context -> {
                    assertThat(context).hasSingleBean(WebIdempotencyConfig.class);
                    assertThat(context.getBean(WebIdempotencyConfig.class)).isSameAs(customWebConfig);
                });
    }

    @Test
    void When_NoCustomSanitizerBean_Expect_DefaultSanitizerRegistered() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ResponseSanitizer.class);
        });
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
    void When_LifecycleListenerBeanPresent_Expect_WiredIntoEngine() {
        contextRunner
                .withBean(IdempotencyStore.class, IdempotencyAutoConfigurationTest::acquiringStore)
                .withBean(IdempotencyLifecycleListener.class, () -> new NamedListener("only"))
                .run(context -> {
                    context.getBean(IdempotencyEngine.class).execute(anyContext(), () -> {});
                    assertThat(listenerCalls).containsExactly("only");
                });
    }

    @Test
    void When_OrderedListenerBeans_Expect_EngineHonoursOrderAnnotation() {
        contextRunner
                .withBean(IdempotencyStore.class, IdempotencyAutoConfigurationTest::acquiringStore)
                .withUserConfiguration(OrderedListenersConfig.class)
                .run(context -> {
                    context.getBean(IdempotencyEngine.class).execute(anyContext(), () -> {});
                    assertThat(listenerCalls).containsExactly("first", "second");
                });
    }

    @Test
    void When_NoLifecycleListenerBean_Expect_EngineStillCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, IdempotencyAutoConfigurationTest::acquiringStore)
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotencyEngine.class);
                    assertThat(context).doesNotHaveBean(IdempotencyLifecycleListener.class);
                });
    }

    private static IdempotencyContext anyContext() {
        return IdempotencyContext.builder("ListenerScope.handle", "listener-key")
                .ttl(Duration.ofHours(1))
                .leaseDuration(Duration.ofSeconds(5))
                .build();
    }

    private static IdempotencyStore acquiringStore() {
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.tryAcquire(any())).thenReturn(AcquireResult.acquired("test-lease-id"));
        return store;
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderedListenersConfig {

        @Bean
        @Order(2)
        IdempotencyLifecycleListener secondListener() {
            return new NamedListener("second");
        }

        @Bean
        @Order(1)
        IdempotencyLifecycleListener firstListener() {
            return new NamedListener("first");
        }
    }

    private record NamedListener(String name) implements IdempotencyLifecycleListener {

        @Override
        public void onAcquired(IdempotencyContext ctx, String leaseId) {
            listenerCalls.add(name);
        }
    }
}
