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

import io.github.josipmusa.idempotency.core.CompletionMode;
import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import io.github.josipmusa.idempotency.core.IdempotencyEngine;
import io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.TransactionParticipation;
import io.github.josipmusa.idempotency.spring.SpringTransactionParticipation;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The transport-neutral half of the starter: the configuration, the heartbeat scheduler, and
 * the engine every adapter runs through.
 *
 * <p>Nothing here knows about HTTP or about AOP. The filter comes from
 * {@link IdempotencyWebAutoConfiguration} and the method interceptor from
 * {@link IdempotencyMethodAutoConfiguration}, each conditional on its own trigger, so a
 * message-driven application gets an engine without a servlet filter and a web application
 * gets both.
 */
@AutoConfiguration(after = IdempotencyStoreAutoConfiguration.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IdempotencyConfig idempotencyConfig(IdempotencyProperties properties) {
        return IdempotencyConfig.builder()
                .defaultTtl(properties.getDefaultTtl())
                .defaultLeaseDuration(properties.getDefaultLease())
                .defaultWaitTimeout(properties.getDefaultWait())
                .completionFailurePolicy(properties.getCompletionFailurePolicy())
                .defaultCompletionMode(properties.getCompletionMode())
                .build();
    }

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean(name = "idempotencyScheduler")
    public ScheduledExecutorService idempotencyScheduler() {
        int poolSize = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        AtomicInteger counter = new AtomicInteger(0);
        return Executors.newScheduledThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "idempotency-heartbeat-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Lets the engine see the caller's transaction, so {@code completion-mode=join-transaction}
     * and {@code @Idempotent(completion = "join-transaction")} can commit the record with the
     * business writes.
     *
     * <p>Supplied only for a store that can actually use it. Handing one to a store whose
     * {@link IdempotencyStore#supportsTransactionalCompletion()} is {@code false} is an
     * {@link IllegalArgumentException} from the engine's own constructor - which is exactly
     * what should happen when the application asked for joined completion, and exactly what
     * should not happen when it did not. So the participation is created when the store
     * supports it, or when the application asked for joined completion and is therefore owed
     * the engine's explanation of why that cannot work here.
     */
    @Bean
    @ConditionalOnClass(SpringTransactionParticipation.class)
    @ConditionalOnMissingBean(TransactionParticipation.class)
    @ConditionalOnBean(IdempotencyStore.class)
    TransactionParticipation idempotencyTransactionParticipation(
            IdempotencyStore store, IdempotencyProperties properties) {
        boolean joinedRequested = properties.getCompletionMode() == CompletionMode.JOIN_TRANSACTION;
        return store.supportsTransactionalCompletion() || joinedRequested
                ? new SpringTransactionParticipation()
                : TransactionParticipation.none();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(IdempotencyStore.class)
    IdempotencyEngine idempotencyEngine(
            IdempotencyStore idempotencyStore,
            ScheduledExecutorService idempotencyScheduler,
            ObjectProvider<IdempotencyLifecycleListener> lifecycleListeners,
            IdempotencyConfig idempotencyConfig,
            ObjectProvider<TransactionParticipation> transactions) {
        return new IdempotencyEngine(
                idempotencyStore,
                idempotencyScheduler,
                lifecycleListeners.orderedStream().toList(),
                idempotencyConfig,
                transactions.getIfAvailable(TransactionParticipation::none));
    }
}
