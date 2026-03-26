package io.github.josipmusa.idempotency.jdbc;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.idempotency.test.IdempotencyStoreContract;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcIdempotencyStoreTest extends IdempotencyStoreContract {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("idempotency_test");

    @Override
    protected IdempotencyStore store() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL(MYSQL.getJdbcUrl());
        ds.setUser(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        return new JdbcIdempotencyStore(ds, true);
    }
}
