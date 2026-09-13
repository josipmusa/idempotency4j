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

import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.inmemory.InMemoryIdempotencyStore;
import io.github.josipmusa.idempotency.jdbc.ConnectionResolver;
import io.github.josipmusa.idempotency.jdbc.JdbcIdempotencyStore;
import io.github.josipmusa.idempotency.spring.TransactionAwareConnectionResolver;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Builds an {@link IdempotencyStore} from what is on the classpath, unless the application
 * built one itself.
 *
 * <p>Only the JDBC store is detected automatically, and only when a single {@code DataSource}
 * is already in the context: it is the one backend whose infrastructure Spring Boot has
 * necessarily already configured. The Redis store is not autoconfigured - it needs a
 * {@code StatefulRedisConnection<String, byte[]>}, which is raw Lettuce rather than anything
 * Boot produces, so the application declares that bean and the store beside it. The in-memory
 * store is built only when it is asked for by name, because an inbox that forgets on restart
 * is not something an application should end up with by accident.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnMissingBean(IdempotencyStore.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStoreAutoConfiguration.class);

    /**
     * Reports which store the application ended up with, and fails the context when
     * {@code store-type} named a backend that could not be built.
     *
     * <p>Without this, asking for a store the classpath cannot provide is silent: no store
     * means no engine, no engine means no filter, and requests are simply never deduplicated.
     * That is the one failure this library must not have.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({JdbcIdempotencyStore.class, TransactionAwareConnectionResolver.class})
    @Conditional(OnJdbcStoreType.class)
    static class JdbcStoreConfiguration {

        /**
         * The resolver is what makes {@code completion-mode=join-transaction} work: it runs
         * {@code COMPLETE} on the connection the caller's transaction is already bound to, and
         * everything else on a connection of its own. Wiring it here means joined completion
         * behaves correctly without the application having to know the resolver exists.
         */
        @Bean
        @ConditionalOnMissingBean(ConnectionResolver.class)
        @ConditionalOnSingleCandidate(DataSource.class)
        ConnectionResolver idempotencyConnectionResolver(DataSource dataSource) {
            return new TransactionAwareConnectionResolver(dataSource);
        }

        @Bean
        @ConditionalOnSingleCandidate(DataSource.class)
        JdbcIdempotencyStore idempotencyStore(
                DataSource dataSource, IdempotencyProperties properties, ObjectProvider<ConnectionResolver> resolver) {
            boolean initSchema = shouldInitSchema(properties.getJdbc().getInitializeSchema(), dataSource);
            return new JdbcIdempotencyStore(dataSource, initSchema, resolver.getIfAvailable());
        }

        /**
         * Runs once every singleton exists, so a Flyway or Liquibase migration has already had
         * its turn, and warns when the table is still not there: without it every keyed request
         * fails with a 500 and nothing at startup says why. A warning rather than a failure,
         * because the table is external state the application may legitimately create later.
         */
        @Bean
        @ConditionalOnSingleCandidate(DataSource.class)
        SmartInitializingSingleton idempotencyTableChecker(
                ObjectProvider<JdbcIdempotencyStore> store, DataSource dataSource) {
            return () -> {
                if (store.getIfAvailable() == null) {
                    return;
                }
                try (Connection connection = dataSource.getConnection();
                        Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1 FROM idempotency_records WHERE 1 = 0");
                } catch (SQLException e) {
                    log.warn(
                            "The idempotency_records table could not be queried ({}), so every idempotent call will "
                                    + "fail until it exists. Create it from the idempotency-schema-postgresql.sql or "
                                    + "idempotency-schema-mysql.sql file shipped in the idempotency-jdbc jar, or set "
                                    + "idempotency.jdbc.initialize-schema=always to let the store create it.",
                            e.getMessage());
                }
            };
        }

        private static boolean shouldInitSchema(DatabaseInitializationMode mode, DataSource dataSource) {
            return switch (mode) {
                case ALWAYS -> true;
                case NEVER -> false;
                case EMBEDDED -> EmbeddedDatabaseConnection.isEmbedded(dataSource);
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(InMemoryIdempotencyStore.class)
    @ConditionalOnProperty(prefix = "idempotency", name = "store-type", havingValue = "in-memory")
    static class InMemoryStoreConfiguration {

        @Bean
        InMemoryIdempotencyStore idempotencyStore() {
            log.warn("Using the in-memory idempotency store. It deduplicates only within this JVM and forgets "
                    + "everything on restart, so it is suitable for tests and single-node development only.");
            return new InMemoryIdempotencyStore();
        }
    }

    /** Matches when {@code store-type} is left at {@code auto} or set explicitly to {@code jdbc}. */
    static class OnJdbcStoreType extends AnyNestedCondition {

        OnJdbcStoreType() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "idempotency", name = "store-type", havingValue = "auto", matchIfMissing = true)
        static class Auto {}

        @ConditionalOnProperty(prefix = "idempotency", name = "store-type", havingValue = "jdbc")
        static class Jdbc {}
    }
}
