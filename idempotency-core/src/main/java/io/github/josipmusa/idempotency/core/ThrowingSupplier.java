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
 * A supplier that can throw checked exceptions.
 *
 * <p>Used as the action parameter in
 * {@link IdempotencyEngine#execute(IdempotencyContext, ThrowingSupplier, PayloadCodec)}
 * because business logic commonly throws checked exceptions (e.g. {@code IOException})
 * that should propagate to the caller unchanged, not wrapped in {@code RuntimeException}.
 *
 * @param <T> what the action produces, and what a {@link PayloadCodec} turns into a
 *            {@link Payload} the store keeps
 */
@FunctionalInterface
public interface ThrowingSupplier<T> {

    /**
     * Runs the action and returns whatever it produced.
     *
     * @return the action's result; may be {@code null} when there is nothing to replay
     * @throws Exception whatever the action throws, propagated unchanged
     */
    // S112: declaring the broad type is deliberate - this interface exists to stay transparent
    // to whatever the caller's business logic throws, so it cannot narrow to a library type.
    @SuppressWarnings("java:S112")
    T get() throws Exception;
}
