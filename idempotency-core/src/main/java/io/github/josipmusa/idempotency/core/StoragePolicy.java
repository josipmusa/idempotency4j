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
 * Decides, after the action ran, whether its result is stored for duplicates to replay or the
 * key is released instead.
 *
 * <p>Passed to
 * {@link IdempotencyEngine#execute(IdempotencyContext, ThrowingSupplier, PayloadCodec, StoragePolicy)}.
 * The engine asks it once per fresh execution, after the action returned and before the result
 * is encoded; a duplicate, an in-flight refusal or an action that threw never reaches it.
 *
 * <p>Returning {@code false} is permission to run the action again under the same key, with any
 * request body: the record is deleted and the next caller executes as if the key had never been
 * used. It does not undo anything the action did, and it does not roll back a transaction. Use
 * it only for results that mean nothing happened, such as a refusal before any downstream call.
 *
 * <p>Only an explicit {@code false} releases the key. If the policy throws, the engine treats it
 * as a failure to record the completion: it applies the configured
 * {@link CompletionFailurePolicy} and keeps the record, because the action may have done its
 * work.
 *
 * @param <T> what the action produces
 */
@FunctionalInterface
public interface StoragePolicy<T> {

    /**
     * Decides what happens to a fresh result.
     *
     * @param value what the action returned; may be {@code null}
     * @return {@code true} to store it, {@code false} to release the key instead
     * @throws Exception if the decision cannot be made; treated as a completion failure
     */
    // S112: the policy may consult whatever the caller's code throws, like the action itself.
    @SuppressWarnings("java:S112")
    boolean shouldStore(T value) throws Exception;

    /**
     * Stores every result, which is what the engine does without a policy.
     *
     * @param <T> what the action produces
     * @return a policy that always returns {@code true}
     */
    static <T> StoragePolicy<T> always() {
        return value -> true;
    }
}
