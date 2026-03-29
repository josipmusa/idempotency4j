package io.github.josipmusa.idempotency.jdbc;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.idempotency.test.IdempotencyStoreContract;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcIdempotencyStoreTest extends IdempotencyStoreContract {

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

    @Override
    protected IdempotencyStore store() {
        return new JdbcIdempotencyStore(dataSource);
    }
}
