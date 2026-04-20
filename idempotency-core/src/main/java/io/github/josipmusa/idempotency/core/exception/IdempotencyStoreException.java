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
 * Thrown when a store operation fails due to invalid state or
 * infrastructure errors.
 *
 * <p>Common causes:
 * <ul>
 *   <li>Calling {@code complete} or {@code release} on a key that does
 *       not exist or is not IN_PROGRESS (programming error)</li>
 *   <li>The underlying storage (database, Redis) is unreachable</li>
 * </ul>
 */
public class IdempotencyStoreException extends IdempotencyException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyStoreException(String message) {
        super(message);
    }

    public IdempotencyStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
