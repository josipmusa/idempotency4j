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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.IdempotencyConfig;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyEngine;
import io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.Payload;
import io.github.josipmusa.idempotency.jdbc.JdbcIdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The post-processor puts the idempotency interceptor behind whatever advice a bean already
 * has, so joined completion finds its transaction open however the transaction advisor is
 * ordered - no {@code @EnableTransactionManagement(order = ...)} required.
 */
@Testcontainers
class IdempotentBeanPostProcessorTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("idempotency_post_processor");

    private static final Duration ASYNC_DEADLINE = Duration.ofSeconds(10);

    private ScheduledExecutorService scheduler;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS orders (id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("DELETE FROM orders");
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        scheduler.shutdownNow();
    }

    @Test
    void When_TransactionManagementUsesDefaultOrder_Expect_JoinedCompletionCommitsWithBusinessWrite() {
        start(DefaultOrderTransactions.class, MethodLevelInbox.class);
        MethodLevelInbox inbox = context.getBean(MethodLevelInbox.class);

        inbox.handle("order-1");
        inbox.handle("order-1");

        assertThat(inbox.handled()).containsExactly("order-1");
        assertThat(orderCount("order-1")).isOne();
        assertThat(probe("MethodLevelInbox.handle", "order-1")).isInstanceOf(AcquireResult.Duplicate.class);
    }

    @Test
    void When_TransactionAdvisorHasLowestPrecedence_Expect_JoinedCompletionStillInsideTransaction() {
        start(LowestPrecedenceTransactions.class, MethodLevelInbox.class);
        MethodLevelInbox inbox = context.getBean(MethodLevelInbox.class);

        inbox.handle("order-2");
        inbox.handle("order-2");

        assertThat(inbox.handled()).containsExactly("order-2");
        assertThat(lifecycle().events).containsExactly("onCompleted:order-2", "onDuplicate:order-2");
    }

    @Test
    void When_JoinedTransactionRollsBack_Expect_KeyReleasedWithRollbackTerminal() {
        start(DefaultOrderTransactions.class, MethodLevelInbox.class);
        MethodLevelInbox inbox = context.getBean(MethodLevelInbox.class);

        inbox.handleThenRollBack("order-6");

        assertThat(orderCount("order-6")).isZero();
        assertThat(lifecycle().events).containsExactly("onFailed:ROLLBACK:order-6");
        assertThat(probe("MethodLevelInbox.handleThenRollBack", "order-6")).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_TransactionalOnTheClass_Expect_JoinedCompletionCommitsWithBusinessWrite() {
        start(DefaultOrderTransactions.class, ClassLevelInbox.class);
        ClassLevelInbox inbox = context.getBean(ClassLevelInbox.class);

        inbox.handle("order-3");

        assertThat(orderCount("order-3")).isOne();
        assertThat(lifecycle().events).containsExactly("onCompleted:order-3");
        assertThat(probe("ClassLevelInbox.handle", "order-3")).isInstanceOf(AcquireResult.Duplicate.class);
    }

    /**
     * The shape of Spring Modulith's {@code @ApplicationModuleListener}: asynchronous, in a
     * transaction of its own, after the publishing transaction committed. A redelivered event
     * is replayed rather than handled twice.
     */
    @Test
    void When_AsyncTransactionalEventListenerGetsEventTwice_Expect_HandledOnce() throws Exception {
        start(AsyncListenerConfig.class, ModuleListener.class, Publisher.class);
        Publisher publisher = context.getBean(Publisher.class);
        ModuleListener listener = context.getBean(ModuleListener.class);

        publisher.publish(new OrderPlaced("order-4", false));
        awaitTrue(() -> lifecycle().events.contains("onCompleted:order-4"));
        publisher.publish(new OrderPlaced("order-4", false));
        awaitTrue(() -> lifecycle().events.contains("onDuplicate:order-4"));

        assertThat(listener.handled()).containsExactly("order-4");
        assertThat(orderCount("order-4")).isOne();
    }

    @Test
    void When_AsyncTransactionalEventListenerRollsBack_Expect_RetriedOnRedelivery() throws Exception {
        start(AsyncListenerConfig.class, ModuleListener.class, Publisher.class);
        Publisher publisher = context.getBean(Publisher.class);
        ModuleListener listener = context.getBean(ModuleListener.class);

        publisher.publish(new OrderPlaced("order-5", true));
        awaitTrue(() -> lifecycle().events.contains("onFailed:ROLLBACK:order-5"));
        publisher.publish(new OrderPlaced("order-5", false));
        awaitTrue(() -> lifecycle().events.contains("onCompleted:order-5"));

        assertThat(listener.handled()).containsExactly("order-5", "order-5");
        assertThat(orderCount("order-5")).isOne();
    }

    @Test
    void When_PostProcessorRegistered_Expect_EngineStillProcessedByOtherPostProcessors() {
        start(DefaultOrderTransactions.class, MethodLevelInbox.class, RecordingPostProcessor.class);

        assertThat(RecordingPostProcessor.processed).contains("idempotencyEngine", "idempotencyStore");
    }

    @Test
    void When_BeanHasNoIdempotentMethod_Expect_NotProxied() {
        start(DefaultOrderTransactions.class, MethodLevelInbox.class, Plain.class);

        assertThat(AopUtils.isAopProxy(context.getBean(Plain.class))).isFalse();
    }

    private Lifecycle lifecycle() {
        return context.getBean(Lifecycle.class);
    }

    private void start(Class<?>... components) {
        RecordingPostProcessor.processed.clear();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(ScheduledExecutorService.class, () -> scheduler);
        context.register(Wiring.class);
        context.register(components);
        context.refresh();
        jdbc.execute("DELETE FROM idempotency_records");
    }

    /**
     * Whatever the store says for a key right now, without waiting and without keeping it: a
     * probe that acquires the key releases it again.
     */
    private AcquireResult probe(String scope, String key) {
        IdempotencyStore store = context.getBean(IdempotencyStore.class);
        AcquireResult result = store.tryAcquire(IdempotencyContext.builder(scope, key)
                .waitTimeout(Duration.ZERO)
                .build());
        if (result instanceof AcquireResult.Acquired(String leaseId)) {
            store.release(IdempotencyContext.builder(scope, key).build().identity(), leaseId);
        }
        return result;
    }

    private int orderCount(String id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + ASYNC_DEADLINE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + ASYNC_DEADLINE);
            }
            Thread.sleep(20);
        }
    }

    @Configuration
    static class Wiring {

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        IdempotencyStore idempotencyStore(DataSource dataSource) {
            return new JdbcIdempotencyStore(dataSource, true, 25L, new TransactionAwareConnectionResolver(dataSource));
        }

        @Bean
        Lifecycle lifecycle() {
            return new Lifecycle();
        }

        @Bean
        IdempotencyEngine idempotencyEngine(
                IdempotencyStore store, ScheduledExecutorService scheduler, Lifecycle lifecycle) {
            return new IdempotencyEngine(
                    store,
                    scheduler,
                    List.of(lifecycle),
                    IdempotencyConfig.defaults(),
                    new SpringTransactionParticipation());
        }

        @Bean
        IdempotentMethodInterceptor idempotentMethodInterceptor(IdempotencyEngine engine) {
            return new IdempotentMethodInterceptor(engine, IdempotencyConfig.defaults());
        }

        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static IdempotentBeanPostProcessor idempotentBeanPostProcessor(
                ObjectProvider<IdempotentMethodInterceptor> interceptor) {
            return new IdempotentBeanPostProcessor(interceptor::getObject);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    /** What Spring Boot does: no order, which used to tie with the idempotency advisor. */
    @Configuration
    @EnableTransactionManagement
    static class DefaultOrderTransactions {}

    @Configuration
    @EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE)
    static class LowestPrecedenceTransactions {}

    @Configuration
    @EnableTransactionManagement
    @EnableAsync
    static class AsyncListenerConfig {}

    static class MethodLevelInbox {

        private final JdbcTemplate jdbc;
        private final List<String> handled = new CopyOnWriteArrayList<>();

        MethodLevelInbox(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public List<String> handled() {
            return handled;
        }

        @Transactional
        @Idempotent(key = "#orderId", completion = "join-transaction", waitTimeout = "PT0S")
        public void handle(String orderId) {
            handled.add(orderId);
            jdbc.update("INSERT INTO orders (id) VALUES (?)", orderId);
        }

        @Transactional
        @Idempotent(key = "#orderId", completion = "join-transaction", waitTimeout = "PT0S")
        public void handleThenRollBack(String orderId) {
            jdbc.update("INSERT INTO orders (id) VALUES (?)", orderId);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    @Transactional
    static class ClassLevelInbox {

        private final JdbcTemplate jdbc;

        ClassLevelInbox(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Idempotent(key = "#orderId", completion = "join-transaction", waitTimeout = "PT0S")
        public void handle(String orderId) {
            jdbc.update("INSERT INTO orders (id) VALUES (?)", orderId);
        }
    }

    record OrderPlaced(String id, boolean rollBack) {}

    static class Publisher {

        private final ApplicationEventPublisher events;

        Publisher(ApplicationEventPublisher events) {
            this.events = events;
        }

        @Transactional
        public void publish(OrderPlaced event) {
            events.publishEvent(event);
        }
    }

    /** {@code @ApplicationModuleListener}, spelled out: Modulith is not a dependency here. */
    static class ModuleListener {

        private final JdbcTemplate jdbc;
        private final List<String> handled = new CopyOnWriteArrayList<>();

        ModuleListener(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public List<String> handled() {
            return handled;
        }

        @Async
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        @TransactionalEventListener
        @Idempotent(key = "#event.id()", completion = "join-transaction", waitTimeout = "PT0S")
        public void on(OrderPlaced event) {
            handled.add(event.id());
            jdbc.update("INSERT INTO orders (id) VALUES (?) ON CONFLICT DO NOTHING", event.id());
            if (event.rollBack()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
        }
    }

    /** What the engine reported, per key, in order. */
    static class Lifecycle implements IdempotencyLifecycleListener {

        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onCompleted(IdempotencyContext ctx, String leaseId, Payload payload) {
            events.add("onCompleted:" + ctx.identity().key());
        }

        @Override
        public void onFailed(IdempotencyContext ctx, String leaseId, Throwable cause, FailurePhase phase) {
            events.add("onFailed:" + phase + ":" + ctx.identity().key());
        }

        @Override
        public void onDuplicate(IdempotencyContext ctx, Payload payload, Instant completedAt) {
            events.add("onDuplicate:" + ctx.identity().key());
        }
    }

    static class Plain {
        public void work() {
            // nothing to advise
        }
    }

    /** A user post-processor, registered after the idempotency one, that records what it saw. */
    static class RecordingPostProcessor implements BeanPostProcessor {

        static final Set<String> processed = ConcurrentHashMap.newKeySet();

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            processed.add(beanName);
            return bean;
        }
    }
}
