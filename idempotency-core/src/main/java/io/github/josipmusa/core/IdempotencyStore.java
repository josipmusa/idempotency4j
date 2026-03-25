package io.github.josipmusa.core;

import java.time.Duration;

public interface IdempotencyStore {

    AcquireResult tryAcquire(IdempotencyContext context);

    void complete(String key, StoredResponse response, Duration ttl);

    void release(String key);

    void extendLock(String key, Duration extension);
}
