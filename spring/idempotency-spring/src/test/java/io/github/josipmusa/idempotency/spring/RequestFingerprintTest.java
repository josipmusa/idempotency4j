package io.github.josipmusa.idempotency.spring;

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
