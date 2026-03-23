package io.github.josipmusa.core.exception;

public class IdempotencyStoreException extends IdempotencyException {

    public IdempotencyStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
