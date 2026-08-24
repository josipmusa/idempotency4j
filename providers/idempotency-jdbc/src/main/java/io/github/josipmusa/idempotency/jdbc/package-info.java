/**
 * JDBC {@link io.github.josipmusa.idempotency.core.IdempotencyStore} implementation.
 *
 * <p>{@link io.github.josipmusa.idempotency.jdbc.JdbcIdempotencyStore} stores
 * idempotency records in a relational database using plain JDBC. It supports
 * MySQL and PostgreSQL and has no dependency on Spring or any ORM.
 *
 * <p>Schema initialization is enabled by default: the single-argument constructor
 * {@code new JdbcIdempotencyStore(dataSource)} runs the DDL automatically on first use,
 * detecting the database dialect from {@link java.sql.DatabaseMetaData#getDatabaseProductName()}
 * and loading the appropriate bundled script ({@code idempotency-schema-mysql.sql} or
 * {@code idempotency-schema-postgresql.sql}). To manage the schema externally (e.g. via
 * Flyway or Liquibase), pass {@code initSchema = false} to the two-argument constructor.
 * Existing automatically managed schemas are upgraded with the nullable {@code lease_id}
 * ownership-fencing column. Externally managed schemas must apply the same column migration
 * before deploying this version.
 *
 * <p>All expiry and lock decisions use the database server's current timestamp, so application
 * nodes do not need synchronized clocks for lease correctness.
 *
 * <p>All lock coordination (blocking, lock stealing) is handled inside the store
 * using database-level constructs. The engine never polls or waits externally.
 */
package io.github.josipmusa.idempotency.jdbc;
