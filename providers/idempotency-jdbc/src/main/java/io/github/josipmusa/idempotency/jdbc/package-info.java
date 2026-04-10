/**
 * JDBC {@link io.github.josipmusa.core.IdempotencyStore} implementation.
 *
 * <p>{@link io.github.josipmusa.idempotency.jdbc.JdbcIdempotencyStore} stores
 * idempotency records in a relational database using plain JDBC. It supports
 * MySQL and PostgreSQL and has no dependency on Spring or any ORM.
 *
 * <p>The required schema is available in the {@code idempotency-schema-mysql.sql}
 * and {@code idempotency-schema-postgresql.sql} resources bundled with this artifact.
 * The store does not create the schema automatically — callers are responsible for
 * applying the DDL before first use.
 *
 * <p>All lock coordination (blocking, lock stealing) is handled inside the store
 * using database-level constructs. The engine never polls or waits externally.
 */
package io.github.josipmusa.idempotency.jdbc;
