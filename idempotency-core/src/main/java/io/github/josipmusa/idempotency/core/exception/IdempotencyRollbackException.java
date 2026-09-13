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
package io.github.josipmusa.idempotency.core.exception;

import io.github.josipmusa.idempotency.core.IdempotencyIdentity;
import java.io.Serial;

/**
 * The completion was recorded inside the caller's transaction and that transaction rolled
 * back, taking the record with it.
 *
 * <p>Never thrown. It exists so the engine has something to hand
 * {@link io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener#onFailed} as the
 * cause of a
 * {@link io.github.josipmusa.idempotency.core.IdempotencyLifecycleListener.FailurePhase#ROLLBACK}
 * failure, and somewhere to attach a failure to release the lease as a suppressed exception.
 * The rollback itself is the caller's decision, not an error the engine detected.
 */
public class IdempotencyRollbackException extends IdempotencyException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient IdempotencyIdentity identity;

    /**
     * @param identity the identity whose completion rolled back
     */
    public IdempotencyRollbackException(IdempotencyIdentity identity) {
        super("Completion for " + identity + " rolled back with the caller's transaction");
        this.identity = identity;
    }

    /**
     * Returns the identity whose completion rolled back.
     *
     * @return the identity
     */
    public IdempotencyIdentity identity() {
        return identity;
    }
}
