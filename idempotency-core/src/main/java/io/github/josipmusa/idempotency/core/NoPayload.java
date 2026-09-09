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
import java.util.Objects;

/**
 * The payload for an operation that has nothing to replay.
 *
 * <p>Used by non-HTTP callers — message listeners, event handlers — where the
 * completed record exists only to suppress a second execution. A duplicate
 * caller receives this back and simply skips the action.
 *
 * @param completedAt when the original operation completed
 */
public record NoPayload(Instant completedAt) implements IdempotencyPayload {

    public NoPayload {
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    /**
     * Creates a {@code NoPayload} marking completion at the given instant.
     *
     * @param completedAt when the operation completed
     * @return a new payload
     */
    public static NoPayload at(Instant completedAt) {
        return new NoPayload(completedAt);
    }
}
