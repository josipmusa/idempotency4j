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
package io.github.josipmusa.idempotency.spring;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import io.github.josipmusa.idempotency.core.IdempotencyEngine;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

/**
 * A {@code @Transactional} method with joined completion is entered before its transaction
 * starts unless the transaction advisor is ordered ahead of the idempotency advisor. That
 * misordering is a startup failure with instructions, not a first-call surprise.
 */
class IdempotentAdvisorOrderingTest {

    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void When_TransactionAdvisorNotOrderedAhead_Expect_ContextFailsWithInstructions() {
        assertThatThrownBy(() -> start(TiedTransactionManagement.class, JoinedAndTransactional.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JoinedAndTransactional.handle")
                .hasMessageContaining("does not run ahead of the idempotency advisor")
                .hasMessageContaining("@EnableTransactionManagement(order = Ordered.HIGHEST_PRECEDENCE)");
    }

    @Test
    void When_TransactionAdvisorOrderedAhead_Expect_ContextStarts() {
        assertThatCode(() -> start(OrderedTransactionManagement.class, JoinedAndTransactional.class)
                        .close())
                .doesNotThrowAnyException();
    }

    @Test
    void When_JoinedMethodLeavesTheTransactionToItsCaller_Expect_NoOrderingDemand() {
        assertThatCode(() -> start(TiedTransactionManagement.class, JoinedNotTransactional.class)
                        .close())
                .doesNotThrowAnyException();
    }

    @Test
    void When_AutonomousTransactionalMethod_Expect_NoOrderingDemand() {
        assertThatCode(() -> start(TiedTransactionManagement.class, AutonomousAndTransactional.class)
                        .close())
                .doesNotThrowAnyException();
    }

    private AnnotationConfigApplicationContext start(Class<?> transactionConfig, Class<?> bean) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ScheduledExecutorService.class, () -> scheduler);
        context.register(Wiring.class, transactionConfig, bean);
        try {
            context.refresh();
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
        return context;
    }

    @Configuration
    static class Wiring {
        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        IdempotencyStore idempotencyStore() {
            IdempotencyStore store = mock(IdempotencyStore.class);
            when(store.supportsTransactionalCompletion()).thenReturn(true);
            return store;
        }

        @Bean
        IdempotencyEngine idempotencyEngine(IdempotencyStore store, ScheduledExecutorService scheduler) {
            return new IdempotencyEngine(
                    store, scheduler, List.of(), IdempotencyConfig.defaults(), new SpringTransactionParticipation());
        }

        @Bean
        IdempotentMethodInterceptor idempotentMethodInterceptor(IdempotencyEngine engine) {
            return new IdempotentMethodInterceptor(engine, IdempotencyConfig.defaults());
        }

        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        IdempotentAdvisor idempotentAdvisor(IdempotentMethodInterceptor interceptor) {
            return new IdempotentAdvisor(interceptor);
        }
    }

    /** Spring Boot's default: no order, which ties with the idempotency advisor. */
    @Configuration
    @EnableTransactionManagement
    static class TiedTransactionManagement {}

    @Configuration
    @EnableTransactionManagement(order = 0)
    static class OrderedTransactionManagement {}

    static class JoinedAndTransactional {
        @Transactional
        @Idempotent(key = "#id", completion = "join-transaction")
        public void handle(String id) {
            // noop
        }
    }

    static class JoinedNotTransactional {
        @Idempotent(key = "#id", completion = "join-transaction")
        public void handle(String id) {
            // noop
        }
    }

    static class AutonomousAndTransactional {
        @Transactional
        @Idempotent(key = "#id")
        public void handle(String id) {
            // noop
        }
    }
}
