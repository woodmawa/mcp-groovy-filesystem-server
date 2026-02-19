package com.softwood.mcp.promise

import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Function

/**
 * Lightweight Promise interface for MCP async operations.
 *
 * Self-contained module — no external dependencies.
 * Built on CompletableFuture + virtual threads (Java 25).
 *
 * v0.0.7 — Phase 1 Foundation
 */
interface Promise<T> {

    /**
     * Transform the result when complete (non-blocking, runs on virtual thread)
     */
    <R> Promise<R> then(Function<T, R> fn)

    /**
     * Register a callback invoked on successful completion
     */
    Promise<T> onComplete(Consumer<T> callback)

    /**
     * Register a callback invoked on failure
     */
    Promise<T> onError(Consumer<Throwable> callback)

    /**
     * Block until the promise resolves, with a timeout.
     * Throws RuntimeException if timeout expires or promise fails.
     */
    T get(long timeout, TimeUnit unit)

    /**
     * Block indefinitely until the promise resolves.
     * Prefer get(timeout, unit) for production use.
     */
    T get()

    /**
     * Returns true if the promise has completed (success or failure)
     */
    boolean isDone()

    /**
     * Returns true if the promise completed exceptionally
     */
    boolean isFailed()

    /**
     * Cancel the underlying computation if not yet started
     */
    boolean cancel()
}
