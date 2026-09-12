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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.josipmusa.idempotency.core.Payload;
import io.github.josipmusa.idempotency.core.exception.IdempotencyCorruptRecordException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class StoredResponseCodecTest {

    private final StoredResponseCodec codec = new StoredResponseCodec();

    @Test
    void When_Encoded_Expect_HttpResponseType() {
        Payload payload = codec.encode(new StoredResponse(201, Map.of(), new byte[0]));

        assertThat(payload.type()).isEqualTo("http/response");
    }

    @Test
    void When_Encoded_Expect_BodyCarriedAsPayloadBody() {
        byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);

        Payload payload = codec.encode(new StoredResponse(200, Map.of(), body));

        assertThat(payload.body()).isEqualTo(body);
    }

    @Test
    void When_Encoded_Expect_StatusInAttributes() {
        Payload payload = codec.encode(new StoredResponse(409, Map.of(), new byte[0]));

        assertThat(payload.attributes()).containsEntry("status", "409");
    }

    @Test
    void When_HeaderHasMultipleValues_Expect_AllSurviveRoundTrip() {
        Map<String, List<String>> headers =
                Map.of("Set-Cookie", List.of("a=1; Path=/", "b=2; Path=/"), "X-Trace", List.of("t-1"));
        StoredResponse original = new StoredResponse(200, headers, "ok".getBytes(StandardCharsets.UTF_8));

        StoredResponse decoded = codec.decode(codec.encode(original));

        assertThat(decoded.headers()).isEqualTo(headers);
    }

    @Test
    void When_HeaderValueContainsComma_Expect_NotSplitOnRoundTrip() {
        Map<String, List<String>> headers = Map.of("Vary", List.of("Accept-Encoding, Accept-Language"));

        StoredResponse decoded = codec.decode(codec.encode(new StoredResponse(200, headers, new byte[0])));

        assertThat(decoded.headers()).isEqualTo(headers);
    }

    @Test
    void When_BodyEmpty_Expect_RoundTripsAsEmpty() {
        StoredResponse original = new StoredResponse(204, Map.of("X-Trace", List.of("t-1")), new byte[0]);

        StoredResponse decoded = codec.decode(codec.encode(original));

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.body()).isEmpty();
    }

    @Test
    void When_NoHeaders_Expect_RoundTripsAsEmptyHeaders() {
        StoredResponse decoded = codec.decode(codec.encode(new StoredResponse(200, Map.of(), new byte[0])));

        assertThat(decoded.headers()).isEmpty();
    }

    @Test
    void When_BodyIsBinary_Expect_RoundTripIntact() {
        byte[] binary = {0, -1, -128, 127, 65, 0, -17, -69, -65};

        StoredResponse decoded = codec.decode(codec.encode(new StoredResponse(200, Map.of(), binary)));

        assertThat(decoded.body()).isEqualTo(binary);
    }

    @Test
    void When_SanitizerConfigured_Expect_AppliedDuringEncode() {
        StoredResponseCodec sanitizing = new StoredResponseCodec(response -> new StoredResponse(
                response.statusCode(),
                response.headers().entrySet().stream()
                        .filter(e -> !e.getKey().equalsIgnoreCase("X-Secret"))
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)),
                response.body()));
        StoredResponse original =
                new StoredResponse(200, Map.of("X-Secret", List.of("token"), "X-Keep", List.of("yes")), new byte[0]);

        StoredResponse decoded = sanitizing.decode(sanitizing.encode(original));

        assertThat(decoded.headers()).doesNotContainKey("X-Secret").containsEntry("X-Keep", List.of("yes"));
    }

    @Test
    void When_SanitizerReturnsNull_Expect_Rejected() {
        StoredResponseCodec nullSanitizing = new StoredResponseCodec(response -> null);

        assertThatThrownBy(() -> nullSanitizing.encode(new StoredResponse(200, Map.of(), new byte[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null");
    }

    @Test
    void When_PayloadIsNotAnHttpResponse_Expect_Rejected() {
        assertThatThrownBy(() -> codec.decode(Payload.none()))
                .isInstanceOf(IdempotencyCorruptRecordException.class)
                .hasMessageContaining("none");
    }

    @Test
    void When_StatusAttributeMissing_Expect_Rejected() {
        Payload payload = new Payload("http/response", new byte[0], Map.of());

        assertThatThrownBy(() -> codec.decode(payload))
                .isInstanceOf(IdempotencyCorruptRecordException.class)
                .hasMessageContaining("status");
    }

    @Test
    void When_StatusAttributeMalformed_Expect_Rejected() {
        Payload payload = new Payload("http/response", new byte[0], Map.of("status", "two hundred"));

        assertThatThrownBy(() -> codec.decode(payload))
                .isInstanceOf(IdempotencyCorruptRecordException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void When_HeadersAttributeMalformed_Expect_Rejected() {
        Payload payload = new Payload("http/response", new byte[0], Map.of("status", "200", "headers", "not-json"));

        assertThatThrownBy(() -> codec.decode(payload))
                .isInstanceOf(IdempotencyCorruptRecordException.class)
                .hasMessageContaining("malformed");
    }
}
