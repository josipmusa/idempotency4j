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
package io.github.josipmusa.idempotency.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.josipmusa.idempotency.core.AcquireResult;
import io.github.josipmusa.idempotency.core.IdempotencyContext;
import io.github.josipmusa.idempotency.core.IdempotencyStore;
import io.github.josipmusa.idempotency.core.Payload;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * What a store must do when {@code complete} runs inside a transaction the caller opened.
 *
 * <p>Only stores whose {@link IdempotencyStore#supportsTransactionalCompletion()} is
 * {@code true} extend this - it is the second contract, not a replacement for
 * {@link IdempotencyStoreContract}, and a store must pass both.
 *
 * <p>The guarantee under test is the receiving-side one: the record and whatever else the
 * transaction wrote become durable together. A completion that has not committed yet is not a
 * completion, and a completion that rolled back never happened - the record goes back to being
 * IN_PROGRESS, exactly as if the action had never returned.
 *
 * <p>A subclass supplies the two halves of the harness: a store whose {@code COMPLETE} runs on
 * the transaction {@link #begin()} opens, and that {@code begin()} itself.
 */
// Test methods follow the project's When_Context_Expect_Result convention. This class is a
// JUnit base class that ships as a published artifact, so it lives in main sources and static
// analysis applies its production naming rule to it.
@SuppressWarnings("java:S100")
public abstract class TransactionalStoreContract {

    /** The scope every fixture in this contract uses. */
    protected static final String SCOPE = "TransactionalContractScope.action";

    /** The payload type every fixture uses; a store must round-trip it verbatim. */
    protected static final String PAYLOAD_TYPE = "test/sample";

    /** How long a completed record stays replayable in these fixtures. */
    protected static final Duration TTL = Duration.ofHours(1);

    /** How long a second caller is given to prove it cannot see an uncommitted completion. */
    private static final Duration UNCOMMITTED_LOOK = Duration.ofMillis(500);

    /**
     * Returns the store under test.
     *
     * <p>It must be wired so that its {@code complete} runs on the transaction {@link #begin()}
     * opened, and every other operation runs autonomously. The same instance must come back for
     * the whole of one test.
     *
     * @return the store
     */
    protected abstract IdempotencyStore store();

    /**
     * Opens a transaction that {@link #store()}'s {@code complete} will run inside.
     *
     * @return a handle for finishing it
     * @throws Exception if the transaction could not be started
     */
    protected abstract Transaction begin() throws Exception;

    /** A transaction the test finishes by hand. Closing an unfinished one rolls it back. */
    public interface Transaction extends AutoCloseable {

        /**
         * Commits the transaction.
         *
         * @throws Exception if the commit failed
         */
        void commit() throws Exception;

        /**
         * Rolls the transaction back.
         *
         * @throws Exception if the rollback failed
         */
        void rollback() throws Exception;

        @Override
        void close() throws Exception;
    }

    @Test
    void When_TransactionalStore_Expect_ReportsSupportForTransactionalCompletion() {
        assertThat(store().supportsTransactionalCompletion()).isTrue();
    }

    @Test
    void When_CompleteInsideTransactionAndCommit_Expect_DuplicateVisible() throws Exception {
        IdempotencyStore store = store();
        IdempotencyContext context = context("commit-key");
        String leaseId = acquire(store, context);

        try (Transaction tx = begin()) {
            store.complete(context.identity(), leaseId, samplePayload(), TTL);
            tx.commit();
        }

        AcquireResult second = store.tryAcquire(context("commit-key"));

        assertThat(second).isInstanceOf(AcquireResult.Duplicate.class);
        assertThat(((AcquireResult.Duplicate) second).payload()).isEqualTo(samplePayload());
    }

    @Test
    void When_CompleteInsideTransactionAndRollback_Expect_RecordStillInProgress() throws Exception {
        IdempotencyStore store = store();
        IdempotencyContext context = context("rollback-key");
        String leaseId = acquire(store, context);

        try (Transaction tx = begin()) {
            store.complete(context.identity(), leaseId, samplePayload(), TTL);
            tx.rollback();
        }

        AcquireResult second = store.tryAcquire(contextWithoutWait("rollback-key"));

        assertThat(second).isInstanceOf(AcquireResult.InFlight.class);
    }

    @Test
    void When_CompleteInsideTransactionAndRollbackThenRelease_Expect_RecordAbsent() throws Exception {
        IdempotencyStore store = store();
        IdempotencyContext context = context("rollback-release-key");
        String leaseId = acquire(store, context);

        try (Transaction tx = begin()) {
            store.complete(context.identity(), leaseId, samplePayload(), TTL);
            tx.rollback();
        }
        store.release(context.identity(), leaseId);

        AcquireResult second = store.tryAcquire(context("rollback-release-key"));

        assertThat(second).isInstanceOf(AcquireResult.Acquired.class);
    }

    /**
     * A completion still inside an open transaction must not look like a completion to anyone
     * else.
     *
     * <p>A second caller either blocks on the uncommitted row or is told the key is in flight.
     * Both are correct; being handed the payload is not, and that is what this asserts. The
     * rollback then lets the second caller finish, and it must still not see a duplicate.
     */
    @Test
    void When_CompleteInsideTransaction_Expect_NotVisibleFromOtherConnectionBeforeCommit() throws Exception {
        IdempotencyStore store = store();
        IdempotencyContext context = context("uncommitted-key");
        String leaseId = acquire(store, context);

        ExecutorService other = Executors.newSingleThreadExecutor();
        try (Transaction tx = begin()) {
            store.complete(context.identity(), leaseId, samplePayload(), TTL);

            Future<AcquireResult> pending = other.submit(() -> store.tryAcquire(contextWithoutWait("uncommitted-key")));
            assertNotDuplicate(resultWithin(pending, UNCOMMITTED_LOOK), "while the transaction was still open");

            tx.rollback();

            assertNotDuplicate(resultWithin(pending, Duration.ofSeconds(10)), "after the transaction rolled back");
        } finally {
            other.shutdownNow();
        }
    }

    /**
     * Returns what the pending acquisition produced, or {@code null} if it is still blocked.
     *
     * <p>Still blocked is a pass, not a failure: a store is free to make the second caller wait
     * on the uncommitted row instead of answering it.
     */
    private static AcquireResult resultWithin(Future<AcquireResult> pending, Duration timeout) throws Exception {
        try {
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillBlockedOnTheOpenTransaction) {
            return null;
        }
    }

    /** Passes for a second caller that is still blocked ({@code null}) or was told anything but duplicate. */
    private static void assertNotDuplicate(AcquireResult result, String when) {
        assertThat(result instanceof AcquireResult.Duplicate)
                .withFailMessage("Another connection saw the completion as a duplicate " + when + ": %s", result)
                .isFalse();
    }

    private static String acquire(IdempotencyStore store, IdempotencyContext context) {
        AcquireResult result = store.tryAcquire(context);
        assertThat(result).isInstanceOf(AcquireResult.Acquired.class);
        return ((AcquireResult.Acquired) result).leaseId();
    }

    /** The fixture payload: a non-empty body plus attributes, so both round-trip together. */
    protected static Payload samplePayload() {
        return new Payload(PAYLOAD_TYPE, "hello".getBytes(UTF_8), Map.of("status", "200", "requestId", "abc-123"));
    }

    /** A context whose lease outlives the test, so nothing is stolen while a transaction is open. */
    protected static IdempotencyContext context(String key) {
        return IdempotencyContext.builder(SCOPE, key)
                .ttl(TTL)
                .leaseDuration(Duration.ofSeconds(30))
                .waitTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** The same context with no wait budget, for the caller that must not park. */
    protected static IdempotencyContext contextWithoutWait(String key) {
        return IdempotencyContext.builder(SCOPE, key)
                .ttl(TTL)
                .leaseDuration(Duration.ofSeconds(30))
                .waitTimeout(Duration.ZERO)
                .build();
    }
}
