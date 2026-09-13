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

/**
 * Translates between what an action returns and the {@link Payload} the store keeps.
 *
 * <p>The core never interprets a payload, so every adapter that has something worth
 * replaying supplies a codec for its own shape: the HTTP adapter has one for captured
 * responses, a messaging adapter one for whatever it needs a duplicate to see. A caller
 * with nothing to replay uses {@link #none()}.
 *
 * <p>Implementations must round-trip: {@code decode(encode(value))} has to reproduce a
 * value equivalent to {@code value} for anything the adapter can encode.
 *
 * @param <T> what the guarded action produces
 */
public interface PayloadCodec<T> {

    /**
     * Encodes the action's result into the envelope to store.
     *
     * @param value what the action produced; may be {@code null} if the action produces nothing
     * @return the payload to store; never {@code null}
     */
    Payload encode(T value);

    /**
     * Decodes a stored envelope back into the action's result, for replay to a duplicate caller.
     *
     * @param payload the payload the store returned; never {@code null}
     * @return the decoded value
     */
    T decode(Payload payload);

    /**
     * The codec for an action with nothing to replay: it encodes to {@link Payload#none()}
     * and decodes back to {@code null}.
     *
     * @return the no-payload codec
     */
    static PayloadCodec<Void> none() {
        return NonePayloadCodec.INSTANCE;
    }
}
