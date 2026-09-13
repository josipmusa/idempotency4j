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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
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

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAutoConfiguration.class);

    /**
     * Reports which store the engine ended up with, whether the starter built it or the
     * application declared it, and fails the context when {@code idempotency.store-type} named
     * a backend nothing could build. It lives here rather than in the store autoconfiguration
     * because that one backs off entirely once the application declares a store, and the
     * report is owed in that case too.
     */
    @Bean
    SmartInitializingSingleton idempotencyStoreReporter(
            ApplicationContext applicationContext, IdempotencyProperties properties) {
        return () -> {
            String[] stores = applicationContext.getBeanNamesForType(IdempotencyStore.class);
            IdempotencyProperties.StoreType requested = properties.getStoreType();
            if (stores.length == 0) {
                if (requested == IdempotencyProperties.StoreType.JDBC
                        || requested == IdempotencyProperties.StoreType.IN_MEMORY) {
                    throw new IllegalStateException("idempotency.store-type is "
                            + requested.name().toLowerCase().replace('_', '-')
                            + " but no store could be built. Add the matching provider dependency"
                            + (requested == IdempotencyProperties.StoreType.JDBC
                                    ? " (io.github.josipmusa:idempotency-jdbc) and make sure exactly one DataSource"
                                            + " bean is available."
                                    : " (io.github.josipmusa:idempotency-inmemory)."));
                }
                if (requested == IdempotencyProperties.StoreType.AUTO) {
                    log.warn("No IdempotencyStore bean is present, so idempotency is inactive: no engine, no filter, "
                            + "and no request is deduplicated. Add a provider dependency, or declare a store bean, "
                            + "or set idempotency.store-type=none to silence this.");
                }
                return;
            }
            log.info(
                    "Idempotency store: {}",
                    applicationContext.getBean(stores[0]).getClass().getName());
        };
    }

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
    TransactionParticipation idempotencyTransactionParticipation(IdempotencyStore store) {
        return store.supportsTransactionalCompletion()
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
        requireStoreSupportsDefaultCompletionMode(idempotencyStore, idempotencyConfig);
        return new IdempotencyEngine(
                idempotencyStore,
                idempotencyScheduler,
                lifecycleListeners.orderedStream().toList(),
                idempotencyConfig,
                transactions.getIfAvailable(TransactionParticipation::none));
    }

    /**
     * The engine would refuse a {@code TransactionParticipation} for a store that cannot use
     * one, but its message talks in engine terms. An application that set the property is
     * told about the property.
     */
    private static void requireStoreSupportsDefaultCompletionMode(IdempotencyStore store, IdempotencyConfig config) {
        if (config.defaultCompletionMode() == CompletionMode.JOIN_TRANSACTION
                && !store.supportsTransactionalCompletion()) {
            throw new IllegalStateException("idempotency.completion-mode is join-transaction, but the configured "
                    + "idempotency store (" + store.getClass().getName()
                    + ") cannot complete inside a caller's transaction. Use a store that can, such as the JDBC one, "
                    + "or set idempotency.completion-mode=autonomous.");
        }
    }
}
