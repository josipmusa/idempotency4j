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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayloadTest {

    @Test
    void When_BodyMutatedAfterConstruction_Expect_PayloadUnchanged() {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        Payload payload = new Payload("test/sample", body, Map.of());

        body[0] = 'x';

        assertThat(payload.body()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void When_ReturnedBodyMutated_Expect_PayloadUnchanged() {
        Payload payload = new Payload("test/sample", "hello".getBytes(StandardCharsets.UTF_8), Map.of());

        payload.body()[0] = 'x';

        assertThat(payload.body()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void When_AttributesMutatedAfterConstruction_Expect_PayloadUnchanged() {
        Map<String, String> attributes = new HashMap<>(Map.of("status", "200"));
        Payload payload = new Payload("test/sample", new byte[0], attributes);

        attributes.put("status", "500");

        assertThat(payload.attributes()).containsExactly(Map.entry("status", "200"));
    }

    @Test
    void When_SameTypeBodyAndAttributes_Expect_Equal() {
        Payload one = new Payload("test/sample", "hi".getBytes(StandardCharsets.UTF_8), Map.of("a", "1"));
        Payload two = new Payload("test/sample", "hi".getBytes(StandardCharsets.UTF_8), Map.of("a", "1"));

        assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
    }

    @Test
    void When_BodiesDiffer_Expect_NotEqual() {
        Payload one = new Payload("test/sample", "hi".getBytes(StandardCharsets.UTF_8), Map.of());
        Payload two = new Payload("test/sample", "ho".getBytes(StandardCharsets.UTF_8), Map.of());

        assertThat(one).isNotEqualTo(two);
    }

    @Test
    void When_ToString_Expect_BodyRenderedAsLengthNotArrayIdentity() {
        Payload payload = new Payload("test/sample", "hello".getBytes(StandardCharsets.UTF_8), Map.of());

        assertThat(payload.toString()).contains("test/sample").contains("5 bytes");
    }

    @Test
    void When_TypeBlank_Expect_Rejected() {
        assertThatThrownBy(() -> new Payload("  ", new byte[0], Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void When_TypeNull_Expect_Rejected() {
        assertThatThrownBy(() -> new Payload(null, new byte[0], Map.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void When_BodyNull_Expect_Rejected() {
        assertThatThrownBy(() -> new Payload("test/sample", null, Map.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void When_AttributesNull_Expect_Rejected() {
        assertThatThrownBy(() -> new Payload("test/sample", new byte[0], null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void When_None_Expect_EmptyBodyEmptyAttributesAndNoneType() {
        Payload none = Payload.none();

        assertThat(none.type()).isEqualTo(Payload.TYPE_NONE);
        assertThat(none.body()).isEmpty();
        assertThat(none.attributes()).isEmpty();
    }
}
