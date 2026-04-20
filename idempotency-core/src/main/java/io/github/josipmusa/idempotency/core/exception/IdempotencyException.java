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

import java.io.Serial;

/**
 * Base exception for all idempotency-related errors.
 *
 * <p>Unchecked (extends {@link RuntimeException}) because idempotency
 * failures are infrastructure errors that callers typically cannot
 * recover from in-line — they should propagate to the error handler.
 *
 * @see IdempotencyStoreException for storage/persistence failures
 * @see IdempotencyLockTimeoutException for lock wait timeouts
 */
public class IdempotencyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyException(String message) {
        super(message);
    }

    public IdempotencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
