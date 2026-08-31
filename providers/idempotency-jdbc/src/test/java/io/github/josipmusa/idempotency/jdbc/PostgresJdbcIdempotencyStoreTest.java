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
package io.github.josipmusa.idempotency.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.exception.IdempotencyStoreUnavailableException;
import io.github.josipmusa.idempotency.test.IdempotencyStoreContract;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresJdbcIdempotencyStoreTest extends IdempotencyStoreContract {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("idempotency_test");

    private static DataSource dataSource;

    @BeforeAll
    static void initSchema() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;
        new JdbcIdempotencyStore(dataSource, true);
    }

    @BeforeEach
    void cleanTable() throws SQLException {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM idempotency_records");
        }
    }

    @Override
    protected IdempotencyStore store() {
        return new JdbcIdempotencyStore(dataSource);
    }

    @Test
    void When_InitSchemaCalledTwice_Expect_DoesNotThrow() {
        assertThatCode(() -> new JdbcIdempotencyStore(dataSource, true)).doesNotThrowAnyException();
    }

    @Test
    void When_ExistingSchemaPredatesLeaseFencing_Expect_ColumnMigratedAutomatically() throws SQLException {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE idempotency_records DROP COLUMN lease_id");
        }

        new JdbcIdempotencyStore(dataSource, true);

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            assertThatCode(() -> stmt.executeQuery("SELECT lease_id FROM idempotency_records WHERE 1 = 0"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void When_ConnectionExhausted_Expect_ThrowsIdempotencyStoreException() throws Exception {
        DataSource exhaustedDs = mock(DataSource.class);
        when(exhaustedDs.getConnection()).thenThrow(new SQLException("connection pool exhausted", "08001"));

        JdbcIdempotencyStore failingStore = new JdbcIdempotencyStore(exhaustedDs, false);
        IdempotencyContext context =
                new IdempotencyContext("key", Duration.ofHours(1), Duration.ofSeconds(5), "a".repeat(64));

        assertThat(failingStore).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> failingStore.tryAcquire(context))
                .isInstanceOf(IdempotencyStoreUnavailableException.class);
    }
}
