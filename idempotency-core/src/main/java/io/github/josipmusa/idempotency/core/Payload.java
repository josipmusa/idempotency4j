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

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * What a completed idempotent operation left behind for replay.
 *
 * <p>A transport-neutral envelope: a media-type-like {@code type} that says how to read
 * the bytes, the {@code body} itself, and flat string {@code attributes} for everything
 * that is not the body. The core never interprets any of the three - it stores them and
 * hands them back verbatim to a duplicate caller through
 * {@link AcquireResult.Duplicate}. Deciding what they mean is the adapter's job, through
 * a {@link PayloadCodec}.
 *
 * <p>An HTTP adapter encodes a response as type {@code http/response} with the status and
 * headers in attributes and the response body as the body. A message listener with nothing
 * to replay stores {@link #none()}. A listener that does have something worth carrying
 * forward - the ids of the messages it published, say - puts it in {@code attributes}, where
 * a later duplicate reads it back rather than publishing again.
 *
 * <p>When the original operation completed is not part of the payload: the store records it
 * and reports it alongside, as {@link AcquireResult.Duplicate#completedAt()}.
 *
 * <p>{@code body} is cloned on the way in and on the way out, and {@code attributes} is
 * copied, so a payload cannot be mutated after construction. {@code equals}, {@code hashCode}
 * and {@code toString} treat the body by content rather than by array identity, so two
 * payloads carrying the same bytes are equal.
 *
 * @param type       how to interpret {@code body}; never blank. {@code http/response} for a
 *                   stored HTTP response, {@value #TYPE_NONE} when there is nothing to replay
 * @param body       the payload bytes; empty rather than {@code null} when there are none
 * @param attributes flat correlation and metadata entries, returned verbatim on a duplicate
 */
public record Payload(String type, byte[] body, Map<String, String> attributes) {

    /** The {@link #type()} of a payload that carries nothing to replay. */
    public static final String TYPE_NONE = "none";

    private static final Payload NONE = new Payload(TYPE_NONE, new byte[0], Map.of());

    public Payload {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        body = body.clone();
        attributes = Map.copyOf(attributes);
    }

    /**
     * The payload for an operation that has nothing to replay.
     *
     * <p>The completed record then exists only to suppress a second execution: a duplicate
     * caller receives this back and simply skips the action.
     *
     * @return the shared empty payload of type {@value #TYPE_NONE}
     */
    public static Payload none() {
        return NONE;
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Payload other
                && type.equals(other.type)
                && Arrays.equals(body, other.body)
                && attributes.equals(other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, Arrays.hashCode(body), attributes);
    }

    @Override
    public String toString() {
        return "Payload[type=" + type + ", body=" + body.length + " bytes, attributes=" + attributes + "]";
    }
}
