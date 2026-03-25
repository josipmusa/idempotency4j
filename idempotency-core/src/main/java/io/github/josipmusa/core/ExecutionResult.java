package io.github.josipmusa.core;

public sealed interface ExecutionResult permits ExecutionResult.Executed,
        ExecutionResult.Duplicate {

    // Action ran fresh - adapter should capture response and call store.complete()
    record Executed() implements ExecutionResult {}
    // Action was skipped - adapter should replay this stored response
    record Duplicate(StoredResponse response) implements ExecutionResult {}

    static ExecutionResult executed() { return new Executed(); }
    static ExecutionResult duplicate(StoredResponse response) {
        return new Duplicate(response);
    }
}
