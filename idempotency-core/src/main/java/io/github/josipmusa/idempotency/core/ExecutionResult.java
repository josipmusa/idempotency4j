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
 * The result of {@link IdempotencyEngine#execute} — tells the adapter
 * whether the action ran or was skipped.
 *
 * <p>This is the engine-to-adapter communication type. Unlike
 * {@link AcquireResult} (store-to-engine), this type does not include
 * a lock-timeout case because the engine converts that to an exception.
 */
public sealed interface ExecutionResult permits ExecutionResult.Executed, ExecutionResult.Duplicate {

    /**
     * The action executed successfully. The adapter must now:
     * <ol>
     *   <li>Capture the HTTP response that was written during the action</li>
     *   <li>Call {@link IdempotencyStore#complete} with that response</li>
     *   <li>Return the response to the client</li>
     * </ol>
     */
    record Executed() implements ExecutionResult {}

    /**
     * The action was skipped — a previous request already completed this key.
     * The adapter should replay the attached {@link StoredResponse} to the
     * client as if the action had just run.
     */
    record Duplicate(StoredResponse response) implements ExecutionResult {}

    static ExecutionResult executed() {
        return new Executed();
    }

    static ExecutionResult duplicate(StoredResponse response) {
        return new Duplicate(response);
    }
}
