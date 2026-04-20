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

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void When_SameBody_Expect_SameHash() {
        byte[] body = "{\"amount\":100}".getBytes(StandardCharsets.UTF_8);
        String first = RequestFingerprint.of(body);
        String second = RequestFingerprint.of(body);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    void When_DifferentBody_Expect_DifferentHash() {
        String hash1 = RequestFingerprint.of("{\"amount\":100}".getBytes(StandardCharsets.UTF_8));
        String hash2 = RequestFingerprint.of("{\"amount\":200}".getBytes(StandardCharsets.UTF_8));

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void When_EmptyBody_Expect_ValidHash() {
        String hash = RequestFingerprint.of(new byte[0]);

        assertThat(hash).hasSize(64);
    }
}
