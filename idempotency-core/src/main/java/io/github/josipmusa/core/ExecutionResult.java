package io.github.josipmusa.core;

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
