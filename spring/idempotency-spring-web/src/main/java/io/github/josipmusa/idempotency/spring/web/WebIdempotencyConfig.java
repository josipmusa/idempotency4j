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

    /** The status a request rejected because another caller holds the key gets. */
    public static final int DEFAULT_IN_FLIGHT_STATUS = 409;

    /** Whether a request to an idempotent endpoint must carry a key. */
    public static final boolean DEFAULT_REQUIRED = true;

    private final String keyHeader;
    private final int inFlightStatus;
    private final boolean required;

    private WebIdempotencyConfig(String keyHeader, int inFlightStatus, boolean required) {
        if (keyHeader == null || keyHeader.isBlank()) {
            throw new IllegalArgumentException("keyHeader must not be blank");
        }
        if (!HEADER_TOKEN.matcher(keyHeader).matches()) {
            throw new IllegalArgumentException("keyHeader '" + keyHeader
                    + "' contains characters not permitted in an HTTP header name (RFC 7230 token)");
        }
        if (inFlightStatus < 400 || inFlightStatus > 599) {
            throw new IllegalArgumentException("inFlightStatus must be a 4xx or 5xx status, got: " + inFlightStatus);
        }
        this.keyHeader = keyHeader;
        this.inFlightStatus = inFlightStatus;
        this.required = required;
    }

    /**
     * Returns a builder with all defaults applied.
     *
     * @return a builder for constructing {@link WebIdempotencyConfig}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the configuration with {@code "Idempotency-Key"} as the key header.
     *
     * @return a default config instance
     */
    public static WebIdempotencyConfig defaults() {
        return builder().build();
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
        return builder().keyHeader(keyHeader).build();
    }

    /**
     * The HTTP header name carrying the idempotency key.
     *
     * @return the header name; never blank
     */
    public String keyHeader() {
        return keyHeader;
    }

    /**
     * The status returned when another caller holds the key and did not finish within the
     * wait timeout.
     *
     * <p>409 by default: the request conflicts with one already in progress, and the client
     * is told when to try again through {@code Retry-After} rather than being told the
     * server is down.
     *
     * @return the HTTP status for an in-flight rejection
     */
    public int inFlightStatus() {
        return inFlightStatus;
    }

    /**
     * Whether a request to an idempotent endpoint is rejected with 422 when it carries no key.
     *
     * <p>An application-wide answer rather than a per-endpoint one: whether clients must send
     * a key is a question about the API's contract as a whole, and an API that answers it
     * differently per endpoint is one clients cannot reason about. {@code false} lets a
     * request without a key through unprotected, for an API where idempotency is offered
     * rather than demanded.
     *
     * @return {@code true} when a missing key is an error
     */
    public boolean required() {
        return required;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WebIdempotencyConfig that
                && keyHeader.equals(that.keyHeader)
                && inFlightStatus == that.inFlightStatus
                && required == that.required;
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyHeader, inFlightStatus, required);
    }

    @Override
    public String toString() {
        return "WebIdempotencyConfig{keyHeader='" + keyHeader + "', inFlightStatus=" + inFlightStatus + ", required="
                + required + "}";
    }

    /** Builds a {@link WebIdempotencyConfig}, overriding only what differs from the defaults. */
    public static final class Builder {

        private String keyHeader = DEFAULT_KEY_HEADER;
        private int inFlightStatus = DEFAULT_IN_FLIGHT_STATUS;
        private boolean required = DEFAULT_REQUIRED;

        /**
         * Sets the header carrying the idempotency key.
         *
         * @param keyHeader the header name; must be a non-blank RFC 7230 token
         * @return this builder
         */
        public Builder keyHeader(String keyHeader) {
            this.keyHeader = keyHeader;
            return this;
        }

        /**
         * Sets the status for a request rejected because another caller holds the key.
         *
         * @param inFlightStatus a 4xx or 5xx status; defaults to {@value WebIdempotencyConfig#DEFAULT_IN_FLIGHT_STATUS}
         * @return this builder
         */
        public Builder inFlightStatus(int inFlightStatus) {
            this.inFlightStatus = inFlightStatus;
            return this;
        }

        /**
         * Sets whether a request without an idempotency key is rejected.
         *
         * @param required {@code false} to let unkeyed requests through unprotected;
         *                 defaults to {@value WebIdempotencyConfig#DEFAULT_REQUIRED}
         * @return this builder
         */
        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        /**
         * Constructs the {@link WebIdempotencyConfig} with the configured values.
         *
         * @return a new immutable config instance
         * @throws IllegalArgumentException if the key header is blank or not a valid header
         *         name, or the in-flight status is outside 400-599
         */
        public WebIdempotencyConfig build() {
            return new WebIdempotencyConfig(keyHeader, inFlightStatus, required);
        }
    }
}
