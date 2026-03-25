package io.github.josipmusa.idempotency.inmemory;

import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.idempotency.test.IdempotencyStoreContract;
import org.junit.jupiter.api.AfterEach;

class InMemoryIdempotencyStoreTest extends IdempotencyStoreContract {

    private InMemoryIdempotencyStore currentStore;

    @Override
    protected IdempotencyStore store() {
        currentStore = new InMemoryIdempotencyStore();
        return currentStore;
    }

    @AfterEach
    void tearDown() {
        if (currentStore != null) {
            currentStore.close();
        }
    }
}
