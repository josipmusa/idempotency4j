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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.josipmusa.idempotency.core.Payload;
import io.github.josipmusa.idempotency.core.PayloadCodec;
import io.github.josipmusa.idempotency.core.exception.IdempotencyCorruptRecordException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Translates a captured HTTP response to and from the core's transport-neutral
 * {@link Payload}.
 *
 * <p>The status code and headers travel as payload attributes and the response body as the
 * payload body, under type {@value #TYPE}. Headers are a multi-map and attributes are flat
 * strings, so the header map is carried as JSON in the single {@value #ATTRIBUTE_HEADERS}
 * attribute rather than flattened, which would lose a header carrying several values or a
 * value containing a comma.
 *
 * <p>{@link #encode} applies the configured {@link ResponseSanitizer} first: sanitizing here
 * rather than at the call site means nothing can reach a store without having passed through
 * it.
 */
public final class StoredResponseCodec implements PayloadCodec<StoredResponse> {

    /** The {@link Payload#type()} of an encoded HTTP response. */
    public static final String TYPE = "http/response";

    static final String ATTRIBUTE_STATUS = "status";
    static final String ATTRIBUTE_HEADERS = "headers";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, List<String>>> HEADERS_TYPE = new TypeReference<>() {};

    private final ResponseSanitizer sanitizer;

    /** A codec that stores what it is given, unchanged. */
    public StoredResponseCodec() {
        this(response -> response);
    }

    /**
     * @param sanitizer applied to every response on the way into a payload
     */
    public StoredResponseCodec(ResponseSanitizer sanitizer) {
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer must not be null");
    }

    @Override
    public Payload encode(StoredResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        StoredResponse sanitized = sanitizer.sanitize(response);
        if (sanitized == null) {
            throw new IllegalStateException(
                    "ResponseSanitizer " + sanitizer.getClass().getName() + " returned null");
        }
        return new Payload(
                TYPE,
                sanitized.body(),
                Map.of(
                        ATTRIBUTE_STATUS,
                        Integer.toString(sanitized.statusCode()),
                        ATTRIBUTE_HEADERS,
                        headersToJson(sanitized.headers())));
    }

    @Override
    public StoredResponse decode(Payload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (!TYPE.equals(payload.type())) {
            throw new IdempotencyCorruptRecordException(
                    "Cannot decode a payload of type '" + payload.type() + "' as an HTTP response");
        }
        String status = payload.attributes().get(ATTRIBUTE_STATUS);
        if (status == null) {
            throw new IdempotencyCorruptRecordException(
                    "Stored HTTP response has no " + ATTRIBUTE_STATUS + " attribute and cannot be replayed");
        }
        int statusCode;
        try {
            statusCode = Integer.parseInt(status);
        } catch (NumberFormatException e) {
            throw new IdempotencyCorruptRecordException(
                    "Stored HTTP response has a malformed " + ATTRIBUTE_STATUS + " attribute: '" + status + "'", e);
        }
        return new StoredResponse(
                statusCode, jsonToHeaders(payload.attributes().get(ATTRIBUTE_HEADERS)), payload.body());
    }

    private static String headersToJson(Map<String, List<String>> headers) {
        try {
            return OBJECT_MAPPER.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize response headers to JSON", e);
        }
    }

    private static Map<String, List<String>> jsonToHeaders(String json) {
        if (json == null || json.isEmpty() || json.equals("{}")) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, HEADERS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IdempotencyCorruptRecordException("Stored HTTP response headers are malformed", e);
        }
    }
}
