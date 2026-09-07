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

import io.github.josipmusa.idempotency.core.exception.IdempotencyCorruptRecordException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeaderJsonTest {

    @Test
    void When_SingleValuedHeader_Expect_JacksonCompatibleObject() {
        assertThat(HeaderJson.encode(Map.of("Content-Type", List.of("application/json"))))
                .isEqualTo("{\"Content-Type\":[\"application/json\"]}");
    }

    @Test
    void When_EmptyMap_Expect_EmptyJsonObject() {
        assertThat(HeaderJson.encode(Map.of())).isEqualTo("{}");
    }

    @Test
    void When_MultiValuedHeader_Expect_AllValuesInOrder() {
        assertThat(HeaderJson.encode(Map.of("Set-Cookie", List.of("a=1", "b=2"))))
                .isEqualTo("{\"Set-Cookie\":[\"a=1\",\"b=2\"]}");
    }

    @Test
    void When_HeaderWithNoValues_Expect_EmptyArray() {
        assertThat(HeaderJson.encode(Map.of("X-Empty", List.of()))).isEqualTo("{\"X-Empty\":[]}");
    }

    @Test
    void When_MultipleHeaders_Expect_EncodingOrderFollowsMapIteration() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("A", List.of("1"));
        headers.put("B", List.of("2"));
        assertThat(HeaderJson.encode(headers)).isEqualTo("{\"A\":[\"1\"],\"B\":[\"2\"]}");
    }

    @Test
    void When_ValueContainsQuoteOrBackslash_Expect_Escaped() {
        assertThat(HeaderJson.encode(Map.of("X", List.of("say \"hi\" c:\\tmp"))))
                .isEqualTo("{\"X\":[\"say \\\"hi\\\" c:\\\\tmp\"]}");
    }

    @Test
    void When_ValueContainsShorthandControlChars_Expect_ShorthandEscapes() {
        assertThat(HeaderJson.encode(Map.of("X", List.of("a\bb\tc\nd\fe\rf"))))
                .isEqualTo("{\"X\":[\"a\\bb\\tc\\nd\\fe\\rf\"]}");
    }

    @Test
    void When_ValueContainsOtherControlChar_Expect_UnicodeEscape() {
        assertThat(HeaderJson.encode(Map.of("X", List.of("a\u0001b\u001fc"))))
                .isEqualTo("{\"X\":[\"a\\u0001b\\u001Fc\"]}");
    }

    @Test
    void When_KeyNeedsEscaping_Expect_KeyEscapedToo() {
        assertThat(HeaderJson.encode(Map.of("X-\"Odd\"", List.of("v")))).isEqualTo("{\"X-\\\"Odd\\\"\":[\"v\"]}");
    }

    @Test
    void When_ValueIsNonAscii_Expect_RawUtf8NotEscaped() {
        assertThat(HeaderJson.encode(Map.of("X", List.of("héllo → 🎉"))))
                .isEqualTo("{\"X\":[\"héllo → 🎉\"]}");
    }

    @Test
    void When_DecodingEncodedHeaders_Expect_RoundTrip() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of("application/json"));
        headers.put("Set-Cookie", List.of("a=1", "b=2"));
        headers.put("X-Weird", List.of("\"quoted\"\t\\slash\\ héllo 🎉\u0001"));
        headers.put("X-Empty", List.of());

        assertThat(HeaderJson.decode(HeaderJson.encode(headers))).isEqualTo(headers);
    }

    @Test
    void When_DecodingEmptyJsonObject_Expect_EmptyMap() {
        assertThat(HeaderJson.decode("{}")).isEmpty();
    }

    @Test
    void When_JsonHasInsignificantWhitespace_Expect_Ignored() {
        assertThat(HeaderJson.decode("  { \"A\" : [ \"1\" , \"2\" ] , \"B\" : [ ] }  "))
                .isEqualTo(Map.of("A", List.of("1", "2"), "B", List.of()));
    }

    @Test
    void When_JsonUsesEscapesJacksonDoesNotEmit_Expect_Decoded() {
        assertThat(HeaderJson.decode("{\"X\":[\"a\\/b\\u0041\\u00e9\"]}")).isEqualTo(Map.of("X", List.of("a/bAé")));
    }

    @Test
    void When_JsonUsesSurrogatePairEscape_Expect_DecodedToSupplementaryChar() {
        assertThat(HeaderJson.decode("{\"X\":[\"\\ud83c\\udf89\"]}")).isEqualTo(Map.of("X", List.of("🎉")));
    }

    @Test
    void When_DuplicateKeys_Expect_LastWins() {
        assertThat(HeaderJson.decode("{\"X\":[\"first\"],\"X\":[\"second\"]}")).isEqualTo(Map.of("X", List.of("second")));
    }

    @Test
    void When_DecodedHeadersConstructStoredResponse_Expect_Accepted() {
        Map<String, List<String>> decoded = HeaderJson.decode("{\"Content-Type\":[\"text/plain\"]}");
        assertThat(new StoredResponse(200, decoded, new byte[0], java.time.Instant.EPOCH).headers())
                .isEqualTo(Map.of("Content-Type", List.of("text/plain")));
    }

    @Test
    void When_JsonIsNotAnObject_Expect_CorruptRecord() {
        assertThatThrownBy(() -> HeaderJson.decode("[\"nope\"]")).isInstanceOf(IdempotencyCorruptRecordException.class);
    }

    @Test
    void When_HeaderListIsNull_Expect_CorruptRecord() {
        assertThatThrownBy(() -> HeaderJson.decode("{\"X\":null}"))
                .isInstanceOf(IdempotencyCorruptRecordException.class);
    }

    @Test
    void When_HeaderValueIsNotAString_Expect_CorruptRecord() {
        assertThatThrownBy(() -> HeaderJson.decode("{\"X\":[1]}"))
                .isInstanceOf(IdempotencyCorruptRecordException.class);
    }

    @Test
    void When_HeaderValueIsNotAnArray_Expect_CorruptRecord() {
        assertThatThrownBy(() -> HeaderJson.decode("{\"X\":\"v\"}"))
                .isInstanceOf(IdempotencyCorruptRecordException.class);
    }

    @Test
    void When_JsonIsTruncated_Expect_CorruptRecord() {
        assertThatThrownBy(() -> HeaderJson.decode("{\"X\":[\"v\"]"))
                .isInstanceOf(IdempotencyCorruptRecordException.class);
    }

    @Test
    void When_JsonHasTrailingGarbage_Expect_CorruptRecord() {
        assertThatThrownBy(() -> HeaderJson.decode("{\"X\":[\"v\"]}extra"))
                .isInstanceOf(IdempotencyCorruptRecordException.class);
    }

    @Test
    void When_JsonHasUnterminatedString_Expect_CorruptRecord() {
        assertThatThrownBy(() -> HeaderJson.decode("{\"X\":[\"v]}"))
                .isInstanceOf(IdempotencyCorruptRecordException.class);
    }

    @Test
    void When_JsonHasInvalidUnicodeEscape_Expect_CorruptRecord() {
        assertThatThrownBy(() -> HeaderJson.decode("{\"X\":[\"\\uZZZZ\"]}"))
                .isInstanceOf(IdempotencyCorruptRecordException.class);
    }

    @Test
    void When_JsonHasUnknownEscape_Expect_CorruptRecord() {
        assertThatThrownBy(() -> HeaderJson.decode("{\"X\":[\"\\q\"]}"))
                .isInstanceOf(IdempotencyCorruptRecordException.class);
    }

    @Test
    void When_JsonIsBlank_Expect_CorruptRecord() {
        assertThatThrownBy(() -> HeaderJson.decode("")).isInstanceOf(IdempotencyCorruptRecordException.class);
    }
}
