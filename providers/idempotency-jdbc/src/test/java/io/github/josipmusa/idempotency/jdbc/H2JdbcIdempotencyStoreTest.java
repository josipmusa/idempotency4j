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

import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.test.IdempotencyStoreContract;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The store on H2, the embedded database a Spring Boot application gets in development and the one
 * the starter's {@code initialize-schema=embedded} default creates the table on. H2 is neither MySQL
 * nor PostgreSQL, so this is also the contract run for every database the store has no dialect for.
 */
class H2JdbcIdempotencyStoreTest extends IdempotencyStoreContract {

    private static DataSource dataSource;

    @BeforeAll
    static void initSchema() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:idempotency-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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

    /** The transactional half of the store contract, on the same database. */
    @Nested
    class TransactionalCompletion extends JdbcTransactionalStoreContract {

        @Override
        protected DataSource dataSource() {
            return dataSource;
        }
    }
}
