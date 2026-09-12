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

import java.io.Serializable;
import java.util.Objects;

/**
 * What a store dedupes on: a {@code scope} and a {@code key}, together.
 *
 * <p>The key alone is not an identity. The same message id delivered to two consumers,
 * or the same {@code Idempotency-Key} header sent to two endpoints, is two units of
 * work and must produce two records. The scope names the unit of work - a handler
 * method, a listener, a job - and the key names the occurrence within it.
 *
 * <p>Stores key their records by the whole identity and never by the key alone.
 *
 * <p>Serializable so that exceptions carrying an identity stay serializable.
 *
 * <p>A scope may not contain {@code ':'}. Stores compose the two halves into a single
 * string - the Redis layout is {@code <prefix>rec:<scope>:<key>} - and that composition is
 * only unambiguous while the scope has no separator in it. Without the restriction, scope
 * {@code a:b} with key {@code c} and scope {@code a} with key {@code b:c} would share one
 * record, which would let a client-controlled key reach across scopes. Keys may contain
 * colons: once the scope cannot, the first colon after the scope always ends it.
 *
 * @param scope names the unit of work, for example {@code PaymentController.create}.
 *              Non-blank, at most {@value #MAX_SCOPE_LENGTH} characters, and free of
 *              {@code ':'}.
 * @param key   names the occurrence within that scope, typically a client-supplied
 *              header value or a message identifier. Non-blank, at most
 *              {@value #MAX_KEY_LENGTH} characters.
 */
public record IdempotencyIdentity(String scope, String key) implements Serializable {

    /** Longest scope a store is required to hold. */
    public static final int MAX_SCOPE_LENGTH = 128;

    /** Longest key a store is required to hold. */
    public static final int MAX_KEY_LENGTH = 255;

    public IdempotencyIdentity {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(key, "key must not be null");
        if (scope.isBlank()) {
            throw new IllegalArgumentException("scope must not be blank");
        }
        if (scope.length() > MAX_SCOPE_LENGTH) {
            throw new IllegalArgumentException("scope length must not exceed " + MAX_SCOPE_LENGTH + " characters");
        }
        if (scope.indexOf(':') >= 0) {
            throw new IllegalArgumentException("scope must not contain ':', got: " + scope);
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("key length must not exceed " + MAX_KEY_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return scope + "/" + key;
    }
}
