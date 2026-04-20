/**
 * Contract test suite for {@link io.github.josipmusa.idempotency.core.IdempotencyStore} implementations.
 *
 * <p>{@link io.github.josipmusa.idempotency.test.IdempotencyStoreContract} is an abstract
 * JUnit 5 test class that every {@code IdempotencyStore} implementation must extend.
 * It is the single source of truth for store behavior — adding a case here automatically
 * tests all implementations.
 *
 * <p>Usage:
 * <pre>{@code
 * class MyIdempotencyStoreTest extends IdempotencyStoreContract {
 *     @Override
 *     protected IdempotencyStore store() {
 *         return new MyIdempotencyStore(...);
 *     }
 * }
 * }</pre>
 *
 * <p>This artifact ships with {@code compile} scope for JUnit and AssertJ so that
 * concrete test classes inherit those dependencies. Consumers should declare it with
 * {@code <scope>test</scope>}.
 */
package io.github.josipmusa.idempotency.test;
