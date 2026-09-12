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

import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.inmemory.InMemoryIdempotencyStore;
import io.github.josipmusa.idempotency.spring.Idempotent;
import io.github.josipmusa.idempotency.spring.IdempotentAdvisor;
import io.github.josipmusa.idempotency.spring.IdempotentMethodInterceptor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The method-level autoconfiguration: active wherever Spring AOP is, with no transport. */
class IdempotencyMethodAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class,
                    IdempotencyAutoConfiguration.class,
                    IdempotencyMethodAutoConfiguration.class));

    @Test
    void When_AopPresentAndStoreBeanPresent_Expect_AdvisorCreated() {
        contextRunner
                .withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotentMethodInterceptor.class);
                    assertThat(context).hasSingleBean(IdempotentAdvisor.class);
                });
    }

    @Test
    void When_AopAbsent_Expect_AdvisorBacksOff() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(IdempotentAdvisor.class))
                .withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
                .run(context -> assertThat(context).doesNotHaveBean(IdempotentMethodInterceptor.class));
    }

    @Test
    void When_NoStoreBeanPresent_Expect_AdvisorBacksOff() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(IdempotentMethodInterceptor.class);
            assertThat(context).doesNotHaveBean(IdempotentAdvisor.class);
        });
    }

    @Test
    void When_AnnotatedMethodCalledTwiceSameKey_Expect_BodyRunsOnce() {
        contextRunner
                .withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
                .withUserConfiguration(ListenerConfig.class)
                .run(context -> {
                    Listener listener = context.getBean(Listener.class);
                    listener.on("msg-1");
                    listener.on("msg-1");

                    assertThat(listener.handled()).containsExactly("msg-1");
                });
    }

    @Test
    void When_MethodAsksForJoinedCompletionAStoreCannotGive_Expect_ContextFailsToStart() {
        // The in-memory store cannot complete inside a caller's transaction, and the global
        // completion-mode is left at its autonomous default, so only the annotation asks for
        // it. Discovering that on the first message - as a complaint that no transaction is
        // active, from inside a method that is demonstrably @Transactional - is no good.
        contextRunner
                .withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
                .withUserConfiguration(JoinedListenerConfig.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("join-transaction")
                        .hasMessageContaining("cannot complete inside a caller's transaction"));
    }

    @Configuration(proxyBeanMethods = false)
    static class JoinedListenerConfig {

        @Bean
        JoinedListener joinedListener() {
            return new JoinedListener();
        }
    }

    static class JoinedListener {

        @Idempotent(key = "#messageId", completion = "join-transaction", waitTimeout = "PT0S")
        public void on(String messageId) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class ListenerConfig {

        @Bean
        Listener listener() {
            return new Listener();
        }
    }

    static class Listener {

        private final List<String> handled = new ArrayList<>();

        @Idempotent(key = "#messageId", waitTimeout = "PT0S")
        public void on(String messageId) {
            handled.add(messageId);
        }

        List<String> handled() {
            return handled;
        }
    }
}
