package io.github.josipmusa.core;

public sealed interface AcquireResult
        permits AcquireResult.Acquired,
        AcquireResult.Duplicate,
        AcquireResult.LockTimeout {

    // Key was new, lock is now held by this caller
    record Acquired() implements AcquireResult {}

    // Key already completed, here is the stored response
    record Duplicate(StoredResponse response) implements AcquireResult {}

    // Key was in-flight, waited lockTimeout, still not done
    record LockTimeout(String key) implements AcquireResult {}

    static AcquireResult acquired() { return new Acquired(); }
    static AcquireResult duplicate(StoredResponse response) { return new Duplicate(response); }
    static AcquireResult lockTimeout(String key) { return new LockTimeout(key); }
}
