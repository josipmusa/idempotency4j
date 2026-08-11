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
package io.github.josipmusa.idempotency.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A Lua script paired with the SHA-1 digest Redis addresses it by.
 *
 * <p>The digest is computed locally at class-initialization time, so the store
 * can issue {@code EVALSHA} without a preceding {@code SCRIPT LOAD} round trip.
 * When Redis has not cached the script yet — first use, or after a restart or
 * {@code SCRIPT FLUSH} — it replies {@code NOSCRIPT} and the caller falls back
 * to {@code EVAL}, which both runs the script and caches it.
 *
 * <p>SHA-1 here is Redis's script-cache addressing scheme, not a security
 * primitive.
 */
final class LuaScript {

    private final String body;

    private final String digest;

    private LuaScript(String body) {
        this.body = body;
        this.digest = digestOf(body);
    }

    static LuaScript of(String body) {
        return new LuaScript(body);
    }

    String body() {
        return body;
    }

    String digest() {
        return digest;
    }

    private static String digestOf(String body) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(sha1.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the Java platform but was unavailable", e);
        }
    }
}
