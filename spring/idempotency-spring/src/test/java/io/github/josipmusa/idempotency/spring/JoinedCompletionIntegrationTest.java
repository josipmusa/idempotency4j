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
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.jdbc.JdbcIdempotencyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The receiving-side guarantee, end to end: an {@code @Transactional} method whose inbox
 * record is written by the engine inside that same transaction, so the record and the
 * business write are one atomic unit - and, for an autonomous one, a record that is written
 * only once that transaction has committed.
 */
@Testcontainers
class JoinedCompletionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("idempotency_joined");

    private static final String SCOPE = "OrderInbox.handle";
    private static final String AUTONOMOUS_SCOPE = "OrderInbox.handleAutonomously";

    private ScheduledExecutorService scheduler;
    private AnnotationConfigApplicationContext context;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private IdempotencyStore verificationStore;

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
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(ScheduledExecutorService.class, () -> scheduler);
        context.register(JoinedCompletionConfig.class);
        context.refresh();

        verificationStore = context.getBean(IdempotencyStore.class);
        jdbc.execute("DELETE FROM idempotency_records");
    }

    @AfterEach
    void tearDown() {
        context.close();
        scheduler.shutdownNow();
    }

    @Test
    void When_JoinedCompletionInsideTransactional_Expect_RowCommittedWithBusinessWrite() {
        OrderInbox inbox = context.getBean(OrderInbox.class);

        inbox.handle("order-1");

        assertThat(orderCount("order-1")).isOne();
        assertThat(verificationStore.tryAcquire(probe("order-1"))).isInstanceOf(AcquireResult.Duplicate.class);
    }

    @Test
    void When_JoinedCompletionAndRedelivered_Expect_BodyRunsOnce() {
        OrderInbox inbox = context.getBean(OrderInbox.class);

        inbox.handle("order-2");
        inbox.handle("order-2");

        assertThat(inbox.handled()).containsExactly("order-2");
        assertThat(orderCount("order-2")).isOne();
    }

    @Test
    void When_JoinedCompletionAndTransactionRollsBack_Expect_RowAbsent() {
        OrderInbox inbox = context.getBean(OrderInbox.class);

        inbox.handleThenRollBack("order-3");

        assertThat(orderCount("order-3")).isZero();
        assertThat(verificationStore.tryAcquire(probe("order-3"))).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void When_AutonomousCompletionInsideTransactional_Expect_RecordAfterCommit() {
        OrderInbox inbox = context.getBean(OrderInbox.class);

        inbox.handleAutonomously("order-4");
        inbox.handleAutonomously("order-4");

        assertThat(inbox.handled()).containsExactly("order-4");
        assertThat(orderCount("order-4")).isOne();
        assertThat(verificationStore.tryAcquire(probe(AUTONOMOUS_SCOPE, "order-4")))
                .isInstanceOf(AcquireResult.Duplicate.class);
    }

    /**
     * The rollback undid the work, so the key must be free again at once. Before completions
     * waited for the transaction, the record joined it, rolled back to IN_PROGRESS with it, and
     * turned every retry away as in flight until the lease expired.
     */
    @Test
    void When_AutonomousCompletionAndTransactionRollsBack_Expect_KeyRetryable() {
        OrderInbox inbox = context.getBean(OrderInbox.class);

        inbox.handleAutonomouslyThenRollBack("order-5");

        assertThat(orderCount("order-5")).isZero();
        assertThat(verificationStore.tryAcquire(probe(AUTONOMOUS_SCOPE, "order-5")))
                .isInstanceOf(AcquireResult.Acquired.class);
    }

    private int orderCount(String id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private static IdempotencyContext probe(String key) {
        return probe(SCOPE, key);
    }

    private static IdempotencyContext probe(String scope, String key) {
        return IdempotencyContext.builder(scope, key).waitTimeout(Duration.ZERO).build();
    }

    @Configuration
    @EnableTransactionManagement
    static class JoinedCompletionConfig {

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        IdempotencyStore idempotencyStore(DataSource dataSource) {
            return new JdbcIdempotencyStore(dataSource, true, 25L, new TransactionAwareConnectionResolver(dataSource));
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
        static IdempotentBeanPostProcessor idempotentBeanPostProcessor(
                ObjectProvider<IdempotentMethodInterceptor> interceptor) {
            return new IdempotentBeanPostProcessor(interceptor::getObject);
        }

        @Bean
        OrderInbox orderInbox(DataSource dataSource) {
            return new OrderInbox(new JdbcTemplate(dataSource));
        }
    }

    static class OrderInbox {

        private final JdbcTemplate jdbc;
        private final List<String> handled = new CopyOnWriteArrayList<>();

        OrderInbox(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        /** Read through a method, not the field: the bean under test is a proxy. */
        public List<String> handled() {
            return handled;
        }

        @Transactional
        @Idempotent(key = "#orderId", scope = SCOPE, completion = "join-transaction", waitTimeout = "PT0S")
        public void handle(String orderId) {
            handled.add(orderId);
            jdbc.update("INSERT INTO orders (id) VALUES (?)", orderId);
        }

        @Transactional
        @Idempotent(key = "#orderId", scope = SCOPE, completion = "join-transaction", waitTimeout = "PT0S")
        public void handleThenRollBack(String orderId) {
            jdbc.update("INSERT INTO orders (id) VALUES (?)", orderId);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }

        @Transactional
        @Idempotent(key = "#orderId", scope = AUTONOMOUS_SCOPE, waitTimeout = "PT0S")
        public void handleAutonomously(String orderId) {
            handled.add(orderId);
            jdbc.update("INSERT INTO orders (id) VALUES (?)", orderId);
        }

        @Transactional
        @Idempotent(key = "#orderId", scope = AUTONOMOUS_SCOPE, waitTimeout = "PT0S")
        public void handleAutonomouslyThenRollBack(String orderId) {
            jdbc.update("INSERT INTO orders (id) VALUES (?)", orderId);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }
}
