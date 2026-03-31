package io.github.josipmusa.idempotency.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.github.josipmusa.core.IdempotencyContext;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.core.exception.IdempotencyStoreException;
import io.github.josipmusa.idempotency.test.IdempotencyStoreContract;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MysqlJdbcIdempotencyStoreTest extends IdempotencyStoreContract {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("idempotency_test");

    private static DataSource dataSource;

    @BeforeAll
    static void initSchema() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL(MYSQL.getJdbcUrl());
        ds.setUser(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
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
    void When_ConnectionExhausted_Expect_ThrowsIdempotencyStoreException() throws Exception {
        DataSource exhaustedDs = mock(DataSource.class);
        when(exhaustedDs.getConnection()).thenThrow(new SQLException("connection pool exhausted", "08001"));

        JdbcIdempotencyStore failingStore = new JdbcIdempotencyStore(exhaustedDs, false);
        IdempotencyContext context =
                new IdempotencyContext("key", Duration.ofHours(1), Duration.ofSeconds(5), "test-fingerprint");

        assertThatThrownBy(() -> failingStore.tryAcquire(context)).isInstanceOf(IdempotencyStoreException.class);
    }

    @Test
    void When_CustomClockProvided_Expect_StoreConstructsSuccessfully() {
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
        var store = new JdbcIdempotencyStore(dataSource, true, 100L, fixedClock);
        assertThat(store).isNotNull();
    }
}
