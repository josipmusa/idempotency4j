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
import io.github.josipmusa.idempotency.core.CompletionMode;
import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyEngine;
import io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.TransactionParticipation;
import io.github.josipmusa.idempotency.spring.SpringTransactionParticipation;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** The transport-neutral autoconfiguration: config, scheduler, engine. */
class IdempotencyAutoConfigurationTest {

    private static final List<String> listenerCalls = new CopyOnWriteArrayList<>();

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class));

    @BeforeEach
    void resetListenerCalls() {
        listenerCalls.clear();
    }

    @Test
    void When_NoStoreBeanPresent_Expect_EngineNotCreated() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(IdempotencyEngine.class);
            assertThat(context).hasSingleBean(IdempotencyConfig.class);
        });
    }

    @Test
    void When_StoreBeanPresent_Expect_EngineCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> assertThat(context).hasSingleBean(IdempotencyEngine.class));
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
    void When_DefaultProperties_Expect_AppliedToConfig() {
        contextRunner.run(context -> {
            IdempotencyConfig config = context.getBean(IdempotencyConfig.class);
            assertThat(config.defaultTtl()).isEqualTo(Duration.ofHours(24));
            assertThat(config.defaultLeaseDuration()).isEqualTo(Duration.ofSeconds(30));
            assertThat(config.defaultWaitTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(config.completionFailurePolicy()).isEqualTo(CompletionFailurePolicy.LOG_AND_RETURN);
            assertThat(config.defaultCompletionMode()).isEqualTo(CompletionMode.AUTONOMOUS);
        });
    }

    @Test
    void When_CustomProperties_Expect_AppliedToConfig() {
        contextRunner
                .withPropertyValues(
                        "idempotency.default-ttl=PT2H",
                        "idempotency.default-lease=PT5M",
                        "idempotency.default-wait=PT0S",
                        "idempotency.completion-failure-policy=propagate",
                        "idempotency.completion-mode=join-transaction")
                .run(context -> {
                    IdempotencyConfig config = context.getBean(IdempotencyConfig.class);
                    assertThat(config.defaultTtl()).isEqualTo(Duration.ofHours(2));
                    assertThat(config.defaultLeaseDuration()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(config.defaultWaitTimeout()).isZero();
                    assertThat(config.completionFailurePolicy()).isEqualTo(CompletionFailurePolicy.PROPAGATE);
                    assertThat(config.defaultCompletionMode()).isEqualTo(CompletionMode.JOIN_TRANSACTION);
                });
    }

    @Test
    void When_StoreSupportsTransactionalCompletion_Expect_SpringTransactionParticipationWiredIn() {
        contextRunner
                .withBean(IdempotencyStore.class, IdempotencyAutoConfigurationTest::transactionalStore)
                .run(context -> assertThat(context.getBean(TransactionParticipation.class))
                        .isInstanceOf(SpringTransactionParticipation.class));
    }

    @Test
    void When_StoreDoesNotSupportTransactionalCompletion_Expect_NoParticipationAndEngineStillCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    assertThat(context.getBean(TransactionParticipation.class))
                            .isSameAs(TransactionParticipation.none());
                    assertThat(context).hasSingleBean(IdempotencyEngine.class);
                });
    }

    /**
     * Redis reports {@code supportsTransactionalCompletion() == false} permanently, so asking
     * for joined completion on top of it cannot work. It must say so at startup rather than at
     * the first message, and in the engine's own words.
     */
    @Test
    void When_JoinTransactionWithRedis_Expect_ContextFailsToStart() {
        contextRunner
                .withPropertyValues("idempotency.completion-mode=join-transaction")
                .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("does not support transactional completion"));
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

    private static IdempotencyStore transactionalStore() {
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.supportsTransactionalCompletion()).thenReturn(true);
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
