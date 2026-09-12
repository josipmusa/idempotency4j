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
import io.github.josipmusa.idempotency.inmemory.InMemoryIdempotencyStore;
import io.github.josipmusa.idempotency.jdbc.ConnectionResolver;
import io.github.josipmusa.idempotency.jdbc.JdbcIdempotencyStore;
import io.github.josipmusa.idempotency.spring.TransactionAwareConnectionResolver;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class IdempotencyStoreAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotencyStoreAutoConfiguration.class));

    private final ApplicationContextRunner withDataSource =
            contextRunner.withBean(DataSource.class, IdempotencyStoreAutoConfigurationTest::embeddedDataSource);

    private static DataSource embeddedDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:idem-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    @Test
    void When_JdbcProviderAndDataSourcePresent_Expect_JdbcStoreCreated() {
        withDataSource.run(context -> {
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(JdbcIdempotencyStore.class);
        });
    }

    @Test
    void When_JdbcStoreCreated_Expect_TransactionAwareConnectionResolverWiredIn() {
        withDataSource.run(context -> {
            assertThat(context).hasSingleBean(ConnectionResolver.class);
            assertThat(context.getBean(ConnectionResolver.class))
                    .isInstanceOf(TransactionAwareConnectionResolver.class);
        });
    }

    @Test
    void When_JdbcProviderAbsent_Expect_NoStoreCreated() {
        withDataSource
                .withClassLoader(new FilteredClassLoader(JdbcIdempotencyStore.class))
                .run(context -> assertThat(context).doesNotHaveBean(IdempotencyStore.class));
    }

    @Test
    void When_NoDataSource_Expect_NoStoreCreated() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(IdempotencyStore.class));
    }

    @Test
    void When_StoreTypeIsNone_Expect_NoStoreCreatedEvenWithDataSource() {
        withDataSource
                .withPropertyValues("idempotency.store-type=none")
                .run(context -> assertThat(context).doesNotHaveBean(IdempotencyStore.class));
    }

    @Test
    void When_ApplicationDeclaresItsOwnStore_Expect_NoStoreAutoConfigured() {
        IdempotencyStore own = mock(IdempotencyStore.class);
        withDataSource.withBean(IdempotencyStore.class, () -> own).run(context -> {
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean(IdempotencyStore.class)).isSameAs(own);
        });
    }

    @Test
    void When_StoreTypeIsInMemory_Expect_InMemoryStoreCreated() {
        contextRunner.withPropertyValues("idempotency.store-type=in-memory").run(context -> {
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(InMemoryIdempotencyStore.class);
        });
    }

    @Test
    void When_StoreTypeIsAutoAndOnlyInMemoryAvailable_Expect_NoStoreCreated() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(InMemoryIdempotencyStore.class));
    }

    @Test
    void When_StoreTypeNamesABackendThatCannotBeBuilt_Expect_ContextFailsToStart() {
        contextRunner
                .withPropertyValues("idempotency.store-type=jdbc")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("idempotency.store-type is jdbc"));
    }

    @Test
    void When_InitializeSchemaIsNever_Expect_StoreStillCreatedWithoutTable() {
        withDataSource
                .withPropertyValues("idempotency.jdbc.initialize-schema=never")
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotencyStore.class);
                    assertThat(tableExists(context.getBean(DataSource.class))).isFalse();
                });
    }

    @Test
    void When_InitializeSchemaIsEmbedded_Expect_TableCreatedOnEmbeddedDatabase() {
        withDataSource.run(context ->
                assertThat(tableExists(context.getBean(DataSource.class))).isTrue());
    }

    private static boolean tableExists(DataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection();
                var tables = connection.getMetaData().getTables(null, null, "IDEMPOTENCY_RECORDS", null)) {
            return tables.next();
        }
    }
}
