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
package io.github.josipmusa.idempotency.spring.web;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * HTTP-specific idempotency settings for {@link IdempotencyFilter}.
 *
 * <p>Transport-neutral defaults (TTL, lock timeout) live in
 * {@link io.github.josipmusa.idempotency.core.IdempotencyConfig}; this class holds
 * what only makes sense over HTTP.
 */
public final class WebIdempotencyConfig {

    /** The header name from the IETF idempotency-key draft. */
    public static final String DEFAULT_KEY_HEADER = "Idempotency-Key";

    private static final Pattern HEADER_TOKEN = Pattern.compile("[!#$%&'*+\\-.0-9A-Za-z^_`|~]+");

    private final String keyHeader;

    private WebIdempotencyConfig(String keyHeader) {
        if (keyHeader == null || keyHeader.isBlank()) {
            throw new IllegalArgumentException("keyHeader must not be blank");
        }
        if (!HEADER_TOKEN.matcher(keyHeader).matches()) {
            throw new IllegalArgumentException("keyHeader '" + keyHeader
                    + "' contains characters not permitted in an HTTP header name (RFC 7230 token)");
        }
        this.keyHeader = keyHeader;
    }

    /**
     * Returns the configuration with {@code "Idempotency-Key"} as the key header.
     *
     * @return a default config instance
     */
    public static WebIdempotencyConfig defaults() {
        return new WebIdempotencyConfig(DEFAULT_KEY_HEADER);
    }

    /**
     * Returns a configuration reading the key from the given header.
     *
     * <p>Override the default when your API uses a different convention
     * (e.g. {@code "X-Idempotency-Key"}).
     *
     * @param keyHeader the header name; must be a non-blank RFC 7230 token
     * @return a config instance reading the key from {@code keyHeader}
     * @throws IllegalArgumentException if {@code keyHeader} is blank or is not a valid header name
     */
    public static WebIdempotencyConfig withKeyHeader(String keyHeader) {
        return new WebIdempotencyConfig(keyHeader);
    }

    /**
     * The HTTP header name carrying the idempotency key.
     *
     * @return the header name; never blank
     */
    public String keyHeader() {
        return keyHeader;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WebIdempotencyConfig that && keyHeader.equals(that.keyHeader);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyHeader);
    }

    @Override
    public String toString() {
        return "WebIdempotencyConfig{keyHeader='" + keyHeader + "'}";
    }
}
