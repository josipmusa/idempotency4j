package io.github.josipmusa.core;

@FunctionalInterface
public interface ThrowingRunnable {
    void run() throws Exception;
}
