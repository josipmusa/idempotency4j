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

/**
 * Encodes and decodes HTTP response headers as JSON, for {@link IdempotencyStore}
 * implementations that persist a {@link StoredResponse} in a text or byte column.
 *
 * <p>The format is a JSON object mapping each header name to an array of its values,
 * for example {@code {"Content-Type":["application/json"],"Set-Cookie":["a=1","b=2"]}},
 * and {@code {}} for no headers. Stores are free to use another representation; this
 * exists so they do not each need a JSON library for so small a job.
 *
 * <p>Decoding accepts any valid JSON of that shape. Anything else - a non-object, a
 * header whose value is not an array of strings, a {@code null} header list, malformed
 * escapes, or trailing content - is rejected as a corrupt record, since
 * {@link StoredResponse} refuses null keys, values and elements on construction and
 * therefore no such document could have been written by this library.
 */
public final class HeaderJson {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private HeaderJson() {}

    /**
     * Encodes headers as a JSON object of string keys to string arrays.
     *
     * @param headers the headers to encode, iterated in the map's own order
     * @return the JSON representation, {@code {}} when there are no headers
     */
    public static String encode(Map<String, List<String>> headers) {
        StringBuilder json = new StringBuilder(96).append('{');
        boolean firstHeader = true;
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!firstHeader) {
                json.append(',');
            }
            firstHeader = false;
            encodeString(json, header.getKey());
            json.append(':').append('[');
            boolean firstValue = true;
            for (String value : header.getValue()) {
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
     * Decodes headers previously written by {@link #encode(Map)}.
     *
     * @param json the JSON object to decode
     * @return the headers, preserving the order they appear in the document
     * @throws IdempotencyCorruptRecordException if {@code json} is not a JSON object
     *                                           of string keys to string arrays
     */
    public static Map<String, List<String>> decode(String json) {
        return new Decoder(json).decode();
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

    /** A single-use recursive-descent reader for the header JSON shape. */
    private static final class Decoder {

        private final String json;
        private int pos;

        Decoder(String json) {
            this.json = json;
        }

        Map<String, List<String>> decode() {
            if (json == null) {
                throw corrupt("headers JSON is null");
            }
            skipWhitespace();
            Map<String, List<String>> headers = readObject();
            skipWhitespace();
            if (pos != json.length()) {
                throw corrupt("unexpected trailing content");
            }
            return headers;
        }

        private Map<String, List<String>> readObject() {
            expect('{');
            Map<String, List<String>> headers = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return headers;
            }
            while (true) {
                skipWhitespace();
                String name = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                headers.put(name, readStringArray());
                skipWhitespace();
                char next = peek();
                pos++;
                if (next == '}') {
                    return headers;
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
                return List.copyOf(values);
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
                throw corrupt("unexpected end of headers JSON");
            }
            return json.charAt(pos);
        }

        private IdempotencyCorruptRecordException corrupt(String detail) {
            return new IdempotencyCorruptRecordException("Malformed response headers JSON: " + detail);
        }
    }
}
