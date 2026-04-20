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
 * Thrown by the engine when a duplicate request is detected but the request
 * body fingerprint does not match the one stored with the original request.
 *
 * <p>This indicates the client reused an idempotency key with a different
 * payload. The adapter should return HTTP 422 Unprocessable Entity.
 */
public class IdempotencyFingerprintMismatchException extends IdempotencyException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String key;
    private final String storedFingerprint;
    private final String receivedFingerprint;

    public IdempotencyFingerprintMismatchException(String key, String storedFingerprint, String receivedFingerprint) {
        super("Request fingerprint mismatch for key '" + key + "': stored=" + storedFingerprint + ", received="
                + receivedFingerprint);
        this.key = key;
        this.storedFingerprint = storedFingerprint;
        this.receivedFingerprint = receivedFingerprint;
    }

    public String getKey() {
        return key;
    }

    public String getStoredFingerprint() {
        return storedFingerprint;
    }

    public String getReceivedFingerprint() {
        return receivedFingerprint;
    }
}
