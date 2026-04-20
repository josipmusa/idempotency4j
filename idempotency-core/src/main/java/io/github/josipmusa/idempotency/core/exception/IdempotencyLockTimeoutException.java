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
import java.time.Duration;

/**
 * Thrown by the engine when a second caller's lock timeout expires while
 * waiting for an in-flight request to complete.
 *
 * <p>This means another request holds the key as IN_PROGRESS and did not
 * complete within this caller's {@code lockTimeout}. The adapter should
 * typically return HTTP 409 Conflict or 503 Service Unavailable.
 *
 * <p>Carries the key and timeout for logging and error responses.
 */
public class IdempotencyLockTimeoutException extends IdempotencyException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String key;
    private final Duration lockTimeout;

    public IdempotencyLockTimeoutException(String key, Duration lockTimeout) {
        super("Lock timeout expired for key " + key + " with timeout " + lockTimeout);
        this.key = key;
        this.lockTimeout = lockTimeout;
    }

    public String getKey() {
        return key;
    }

    public Duration getLockTimeout() {
        return lockTimeout;
    }
}
