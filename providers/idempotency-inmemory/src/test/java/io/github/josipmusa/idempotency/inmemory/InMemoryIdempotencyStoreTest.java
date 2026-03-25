package io.github.josipmusa.idempotency.inmemory;

import io.github.josipmusa.core.IdempotencyStore;
import io.github.josipmusa.idempotency.test.IdempotencyStoreContract;

class InMemoryIdempotencyStoreTest extends IdempotencyStoreContract {

    @Override
    protected IdempotencyStore store() {
        return new InMemoryIdempotencyStore();
    }
}
