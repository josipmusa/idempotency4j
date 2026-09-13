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
 * What {@link IdempotencyEngine#execute} does when the action succeeded but the completion
 * could not be recorded.
 *
 * <p>The action already ran and its side effects are durable, so the two options trade the
 * same thing against each other: report the storage failure, or return the result the caller
 * already earned. Either way the record is not left behind - a retry under the same key will
 * execute the action again once the lease expires.
 *
 * <p>Configured through {@link IdempotencyConfig.Builder#completionFailurePolicy}. The
 * default is {@link #PROPAGATE}.
 */
public enum CompletionFailurePolicy {

    /**
     * Rethrow the completion failure. The caller learns that idempotency was not recorded
     * and loses the action's result, which is what a non-HTTP caller driving the engine
     * usually wants.
     */
    PROPAGATE,

    /**
     * Log the completion failure at error and return {@link Outcome.Executed} anyway. The
     * caller keeps the action's result; only the idempotency guarantee is lost. This is what
     * the HTTP filter uses: a response the handler already produced should still reach the
     * client.
     */
    LOG_AND_RETURN
}
