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

import io.github.josipmusa.idempotency.core.exception.IdempotencyCorruptRecordException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * JSON for the flat string maps this library persists, so that neither a store nor an
 * adapter needs a JSON library for so small a job.
 *
 * <p>Two shapes are supported. {@link #encode(Map)} and {@link #decode(String)} handle a
 * string-to-string map, which is how a store keeps {@link Payload#attributes()}:
 * {@code {"status":"200","trace":"abc"}}. {@link #encodeMultimap(Map)} and
 * {@link #decodeMultimap(String)} handle a string-to-string-list map, which is how an adapter
 * folds a multi-valued structure such as HTTP headers into a single attribute:
 * {@code {"Content-Type":["application/json"],"Set-Cookie":["a=1","b=2"]}}. Both encode an
 * empty map as {@code {}}, escape exactly what JSON requires and leave non-ASCII characters
 * as they are, and both preserve iteration order in either direction.
 *
 * <p>Decoding accepts any valid JSON document of the expected shape, including insignificant
 * whitespace and escapes this class never emits. Anything else - a non-object, a value of the
 * wrong type, a {@code null} value, a malformed escape, or trailing content - is rejected as an
 * {@link IdempotencyCorruptRecordException}: {@link Payload} refuses null keys and values on
 * construction, so no such document could have been written by this library.
 */
public final class AttributeJson {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private AttributeJson() {}

    /**
     * Encodes a flat string map as a JSON object.
     *
     * @param attributes the entries to encode, iterated in the map's own order
     * @return the JSON object, {@code {}} when the map is empty
     */
    public static String encode(Map<String, String> attributes) {
        StringBuilder json = new StringBuilder(64).append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            encodeString(json, entry.getKey());
            json.append(':');
            encodeString(json, entry.getValue());
        }
        return json.append('}').toString();
    }

    /**
     * Decodes a JSON object of string values, as written by {@link #encode(Map)}.
     *
     * @param json the document to decode
     * @return the entries, in document order
     * @throws IdempotencyCorruptRecordException if {@code json} is not a JSON object whose
     *                                           every value is a string
     */
    public static Map<String, String> decode(String json) {
        Decoder decoder = new Decoder(json);
        return decoder.decodeDocument(decoder::readString);
    }

    /**
     * Encodes a string multimap as a JSON object of string arrays.
     *
     * @param multimap the entries to encode, iterated in the map's own order
     * @return the JSON object, {@code {}} when the map is empty
     */
    public static String encodeMultimap(Map<String, List<String>> multimap) {
        StringBuilder json = new StringBuilder(96).append('{');
        boolean firstEntry = true;
        for (Map.Entry<String, List<String>> entry : multimap.entrySet()) {
            if (!firstEntry) {
                json.append(',');
            }
            firstEntry = false;
            encodeString(json, entry.getKey());
            json.append(':').append('[');
            boolean firstValue = true;
            for (String value : entry.getValue()) {
                if (!firstValue) {
                    json.append(',');
                }
                firstValue = false;
                encodeString(json, value);
            }
            json.append(']');
        }
        return json.append('}').toString();
    }

    /**
     * Decodes a JSON object of string arrays, as written by {@link #encodeMultimap(Map)}.
     *
     * @param json the document to decode
     * @return the entries, in document order, each list immutable
     * @throws IdempotencyCorruptRecordException if {@code json} is not a JSON object whose
     *                                           every value is an array of strings
     */
    public static Map<String, List<String>> decodeMultimap(String json) {
        Decoder decoder = new Decoder(json);
        return decoder.decodeDocument(decoder::readStringArray);
    }

    private static void encodeString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\t' -> json.append("\\t");
                case '\n' -> json.append("\\n");
                case '\f' -> json.append("\\f");
                case '\r' -> json.append("\\r");
                default -> {
                    if (c < 0x20) {
                        json.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

    /**
     * A single-use recursive-descent reader for a JSON object whose values all share one
     * shape, supplied by the caller as a reader positioned at the value's first character.
     */
    private static final class Decoder {

        private final String json;
        private int pos;

        Decoder(String json) {
            this.json = json;
        }

        <V> Map<String, V> decodeDocument(Supplier<V> valueReader) {
            if (json == null) {
                throw corrupt("document is null");
            }
            skipWhitespace();
            Map<String, V> entries = readObject(valueReader);
            skipWhitespace();
            if (pos != json.length()) {
                throw corrupt("unexpected trailing content at position " + pos);
            }
            return entries;
        }

        private <V> Map<String, V> readObject(Supplier<V> valueReader) {
            expect('{');
            Map<String, V> entries = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return entries;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                entries.put(key, valueReader.get());
                skipWhitespace();
                char next = peek();
                pos++;
                if (next == '}') {
                    return entries;
                }
                if (next != ',') {
                    throw corrupt("expected ',' or '}' at position " + (pos - 1));
                }
            }
        }

        private List<String> readStringArray() {
            expect('[');
            List<String> values = new ArrayList<>(2);
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return List.of();
            }
            while (true) {
                skipWhitespace();
                values.add(readString());
                skipWhitespace();
                char next = peek();
                pos++;
                if (next == ']') {
                    return List.copyOf(values);
                }
                if (next != ',') {
                    throw corrupt("expected ',' or ']' at position " + (pos - 1));
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder value = new StringBuilder(16);
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') {
                    return value.toString();
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                char escape = peek();
                pos++;
                switch (escape) {
                    case '"', '\\', '/' -> value.append(escape);
                    case 'b' -> value.append('\b');
                    case 't' -> value.append('\t');
                    case 'n' -> value.append('\n');
                    case 'f' -> value.append('\f');
                    case 'r' -> value.append('\r');
                    case 'u' -> value.append(readUnicodeEscape());
                    default -> throw corrupt("unsupported escape '\\" + escape + "' at position " + (pos - 2));
                }
            }
        }

        /**
         * Reads the four hex digits of a {@code \\uXXXX} escape. Each escape yields one
         * UTF-16 code unit, so an escaped surrogate pair reassembles naturally.
         */
        private char readUnicodeEscape() {
            if (pos + 4 > json.length()) {
                throw corrupt("truncated unicode escape at position " + pos);
            }
            int codeUnit = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(json.charAt(pos + i), 16);
                if (digit < 0) {
                    throw corrupt("malformed unicode escape at position " + pos);
                }
                codeUnit = (codeUnit << 4) | digit;
            }
            pos += 4;
            return (char) codeUnit;
        }

        private void skipWhitespace() {
            while (pos < json.length()) {
                char c = json.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        private void expect(char expected) {
            char actual = peek();
            if (actual != expected) {
                throw corrupt("expected '" + expected + "' but found '" + actual + "' at position " + pos);
            }
            pos++;
        }

        private char peek() {
            if (pos >= json.length()) {
                throw corrupt("unexpected end of document");
            }
            return json.charAt(pos);
        }

        private static IdempotencyCorruptRecordException corrupt(String detail) {
            return new IdempotencyCorruptRecordException("Malformed attribute JSON: " + detail);
        }
    }
}
