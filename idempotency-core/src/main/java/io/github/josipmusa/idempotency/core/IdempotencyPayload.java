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
package io.github.josipmusa.idempotency.core;

import java.time.Instant;

/**
 * What a completed idempotent operation left behind for replay.
 *
 * <p>Stored by {@link IdempotencyStore#complete} and handed back to a duplicate
 * caller via {@link AcquireResult.Duplicate}. The shape is transport-specific:
 * an HTTP adapter stores a {@link StoredResponse}, while a caller with nothing
 * to replay — a message listener, an event handler — stores {@link NoPayload}
 * and relies on the record alone to suppress re-execution.
 *
 * <p>Handle the cases with pattern matching:
 * <pre>{@code
 * switch (duplicate.payload()) {
 *     case StoredResponse r -> // replay the HTTP response
 *     case NoPayload ignored -> // nothing to replay; just skip the action
 * }
 * }</pre>
 */
public sealed interface IdempotencyPayload permits StoredResponse, NoPayload {

    /**
     * When the original operation completed. Useful for debugging and audit logs.
     *
     * @return the completion instant; never {@code null}
     */
    Instant completedAt();
}
