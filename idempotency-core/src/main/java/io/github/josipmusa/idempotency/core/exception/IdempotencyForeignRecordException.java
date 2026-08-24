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

/** Thrown when a configured namespace resolves to data not owned by idempotency4j. */
public class IdempotencyForeignRecordException extends IdempotencyStoreException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyForeignRecordException(String message) {
        super(message);
    }
}
