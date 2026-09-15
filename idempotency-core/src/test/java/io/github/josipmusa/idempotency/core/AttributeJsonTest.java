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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AttributeJsonTest {

    @Nested
    class FlatMap {

        @Test
        void When_SingleEntry_Expect_JsonObject() {
            assertThat(AttributeJson.encode(Map.of("status", "200"))).isEqualTo("{\"status\":\"200\"}");
        }

        @Test
        void When_EmptyMap_Expect_EmptyJsonObject() {
            assertThat(AttributeJson.encode(Map.of())).isEqualTo("{}");
        }

        @Test
        void When_MultipleEntries_Expect_EncodingOrderFollowsMapIteration() {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("a", "1");
            attributes.put("b", "2");
            assertThat(AttributeJson.encode(attributes)).isEqualTo("{\"a\":\"1\",\"b\":\"2\"}");
        }

        @Test
        void When_EmptyValue_Expect_EmptyString() {
            assertThat(AttributeJson.encode(Map.of("x", ""))).isEqualTo("{\"x\":\"\"}");
        }

        @Test
        void When_ValueContainsQuoteOrBackslash_Expect_Escaped() {
            assertThat(AttributeJson.encode(Map.of("x", "say \"hi\" c:\\tmp")))
                    .isEqualTo("{\"x\":\"say \\\"hi\\\" c:\\\\tmp\"}");
        }

        @Test
        void When_ValueContainsShorthandControlChars_Expect_ShorthandEscapes() {
            assertThat(AttributeJson.encode(Map.of("x", "a\bb\tc\nd\fe\rf")))
                    .isEqualTo("{\"x\":\"a\\bb\\tc\\nd\\fe\\rf\"}");
        }

        @Test
        void When_ValueContainsOtherControlChar_Expect_UnicodeEscape() {
            assertThat(AttributeJson.encode(Map.of("x", "a\u0001b\u001fc"))).isEqualTo("{\"x\":\"a\\u0001b\\u001Fc\"}");
        }

        @Test
        void When_KeyNeedsEscaping_Expect_KeyEscapedToo() {
            assertThat(AttributeJson.encode(Map.of("k\"ey", "v"))).isEqualTo("{\"k\\\"ey\":\"v\"}");
        }

        @Test
        void When_ValueIsNonAscii_Expect_RawNotEscaped() {
            assertThat(AttributeJson.encode(Map.of("x", "héllo → 🎉"))).isEqualTo("{\"x\":\"héllo → 🎉\"}");
        }

        @Test
        void When_DecodingEncodedMap_Expect_RoundTrip() {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("status", "200");
            attributes.put("weird", "\"quoted\"\t\\slash\\ héllo 🎉\u0001");
            attributes.put("empty", "");

            assertThat(AttributeJson.decode(AttributeJson.encode(attributes))).isEqualTo(attributes);
        }

        @Test
        void When_DecodingEmptyJsonObject_Expect_EmptyMap() {
            assertThat(AttributeJson.decode("{}")).isEmpty();
        }

        @Test
        void When_DecodingJacksonOutput_Expect_SameMap() {
            assertThat(AttributeJson.decode("{\"status\":\"201\",\"headers\":\"{\\\"A\\\":[\\\"1\\\"]}\"}"))
                    .isEqualTo(Map.of("status", "201", "headers", "{\"A\":[\"1\"]}"));
        }

        @Test
        void When_JsonHasInsignificantWhitespace_Expect_Ignored() {
            assertThat(AttributeJson.decode("  { \"a\" : \"1\" , \"b\" : \"\" }  "))
                    .isEqualTo(Map.of("a", "1", "b", ""));
        }

        @Test
        void When_JsonUsesEscapesThisClassDoesNotEmit_Expect_Decoded() {
            assertThat(AttributeJson.decode("{\"x\":\"a\\/b\\u0041\\u00e9\"}")).isEqualTo(Map.of("x", "a/bAé"));
        }

        @Test
        void When_JsonUsesSurrogatePairEscape_Expect_DecodedToSupplementaryChar() {
            assertThat(AttributeJson.decode("{\"x\":\"\\ud83c\\udf89\"}")).isEqualTo(Map.of("x", "🎉"));
        }

        @Test
        void When_DuplicateKeys_Expect_LastWins() {
            assertThat(AttributeJson.decode("{\"x\":\"first\",\"x\":\"second\"}"))
                    .isEqualTo(Map.of("x", "second"));
        }

        @Test
        void When_DecodedMapConstructsPayload_Expect_Accepted() {
            Map<String, String> decoded = AttributeJson.decode("{\"status\":\"200\"}");
            assertThat(new Payload("http/response", new byte[0], decoded).attributes())
                    .isEqualTo(Map.of("status", "200"));
        }

        @Test
        void When_JsonIsNotAnObject_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode("[\"nope\"]"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_ValueIsNull_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode("{\"x\":null}"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_ValueIsNotAString_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode("{\"x\":1}"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_ValueIsAnArray_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode("{\"x\":[\"v\"]}"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_JsonIsTruncated_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode("{\"x\":\"v\""))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_JsonHasTrailingGarbage_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode("{\"x\":\"v\"}extra"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_JsonHasUnterminatedString_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode("{\"x\":\"v}"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_JsonHasInvalidUnicodeEscape_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode("{\"x\":\"\\uZZZZ\"}"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_JsonHasTruncatedUnicodeEscape_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode("{\"x\":\"\\u00"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_JsonHasUnknownEscape_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode("{\"x\":\"\\q\"}"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_JsonIsBlank_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode("")).isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_JsonIsNull_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decode(null)).isInstanceOf(IdempotencyCorruptRecordException.class);
        }
    }

    @Nested
    class Multimap {

        @Test
        void When_SingleValuedEntry_Expect_JsonObjectOfArrays() {
            assertThat(AttributeJson.encodeMultimap(Map.of("Content-Type", List.of("application/json"))))
                    .isEqualTo("{\"Content-Type\":[\"application/json\"]}");
        }

        @Test
        void When_EmptyMap_Expect_EmptyJsonObject() {
            assertThat(AttributeJson.encodeMultimap(Map.of())).isEqualTo("{}");
        }

        @Test
        void When_MultiValuedEntry_Expect_AllValuesInOrder() {
            assertThat(AttributeJson.encodeMultimap(Map.of("Set-Cookie", List.of("a=1", "b=2"))))
                    .isEqualTo("{\"Set-Cookie\":[\"a=1\",\"b=2\"]}");
        }

        @Test
        void When_EntryWithNoValues_Expect_EmptyArray() {
            assertThat(AttributeJson.encodeMultimap(Map.of("X-Empty", List.of())))
                    .isEqualTo("{\"X-Empty\":[]}");
        }

        @Test
        void When_MultipleEntries_Expect_EncodingOrderFollowsMapIteration() {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            headers.put("A", List.of("1"));
            headers.put("B", List.of("2"));
            assertThat(AttributeJson.encodeMultimap(headers)).isEqualTo("{\"A\":[\"1\"],\"B\":[\"2\"]}");
        }

        @Test
        void When_ValueNeedsEscaping_Expect_Escaped() {
            assertThat(AttributeJson.encodeMultimap(Map.of("X", List.of("say \"hi\"\n"))))
                    .isEqualTo("{\"X\":[\"say \\\"hi\\\"\\n\"]}");
        }

        @Test
        void When_DecodingEncodedMultimap_Expect_RoundTrip() {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            headers.put("Content-Type", List.of("application/json"));
            headers.put("Set-Cookie", List.of("a=1", "b=2"));
            headers.put("X-Weird", List.of("\"quoted\"\t\\slash\\ héllo 🎉\u0001"));
            headers.put("X-Empty", List.of());

            assertThat(AttributeJson.decodeMultimap(AttributeJson.encodeMultimap(headers)))
                    .isEqualTo(headers);
        }

        @Test
        void When_DecodingEmptyJsonObject_Expect_EmptyMap() {
            assertThat(AttributeJson.decodeMultimap("{}")).isEmpty();
        }

        @Test
        void When_JsonHasInsignificantWhitespace_Expect_Ignored() {
            assertThat(AttributeJson.decodeMultimap("  { \"A\" : [ \"1\" , \"2\" ] , \"B\" : [ ] }  "))
                    .isEqualTo(Map.of("A", List.of("1", "2"), "B", List.of()));
        }

        @Test
        void When_DuplicateKeys_Expect_LastWins() {
            assertThat(AttributeJson.decodeMultimap("{\"X\":[\"first\"],\"X\":[\"second\"]}"))
                    .isEqualTo(Map.of("X", List.of("second")));
        }

        @Test
        void When_JsonIsNotAnObject_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decodeMultimap("[\"nope\"]"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_ValueIsNull_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decodeMultimap("{\"X\":null}"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_ArrayElementIsNotAString_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decodeMultimap("{\"X\":[1]}"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_ValueIsNotAnArray_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decodeMultimap("{\"X\":\"v\"}"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_ArrayIsTruncated_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decodeMultimap("{\"X\":[\"v\""))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_ArrayHasTrailingComma_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decodeMultimap("{\"X\":[\"v\",]}"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_JsonHasTrailingGarbage_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decodeMultimap("{\"X\":[\"v\"]}extra"))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }

        @Test
        void When_JsonIsBlank_Expect_CorruptRecord() {
            assertThatThrownBy(() -> AttributeJson.decodeMultimap(""))
                    .isInstanceOf(IdempotencyCorruptRecordException.class);
        }
    }
}
