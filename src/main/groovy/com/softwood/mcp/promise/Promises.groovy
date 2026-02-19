package com.softwood.mcp.promise

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * Static factory for creating and composing Promise instances.
 *
 * Usage:
 *   def p = Promises.async { expensiveOp() }
 *   def p2 = Promises.newPromise("result")
 *   def all = Promises.all([p1, p2, p3])
 *   def any = Promises.any([p1, p2, p3])
 *
 * v0.0.7 — Phase 1 Foundation
 */
@Slf4j
@CompileStatic
class Promises {

    private Promises() {} // static utility — no instantiation

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Create a pre-resolved promise wrapping an immediate value.
     * Useful for returning from synchronous fast-paths.
     */
    static <T> Promise<T> newPromise(T value) {
        return PromiseImpl.resolved(value)
    }

    /**
     * Create a pre-failed promise from a Throwable.
     */
    static <T> Promise<T> failed(Throwable error) {
        return PromiseImpl.failed(error)
    }

    /**
     * Execute a Callable on a virtual thread, returning a Promise.
     * This is the primary entry point for async MCP operations.
     *
     * Example:
     *   Promises.async { Files.readString(path) }
     */
    static <T> Promise<T> async(Callable<T> task) {
        CompletableFuture<T> cf = CompletableFuture.supplyAsync(
            { ->
                try {
                    return task.call()
                } catch (Exception e) {
                    throw (e instanceof RuntimeException) ? e : new RuntimeException(e)
                }
            } as Supplier<T>,
            PromiseImpl.VIRTUAL_EXECUTOR
        )
        return new PromiseImpl<T>(cf)
    }

    /**
     * Execute a Closure on a virtual thread, returning a Promise.
     * Groovy-friendly alias for async(Callable).
     */
    static <T> Promise<T> async(Closure<T> task) {
        return async({ -> task.call() } as Callable<T>)
    }

    // -----------------------------------------------------------------------
    // Composition
    // -----------------------------------------------------------------------

    /**
     * Wait for ALL promises to complete and return a list of their results.
     * Fails fast if any promise fails.
     */
    static <T> Promise<List<T>> all(List<Promise<T>> promises) {
        if (!promises) {
            return newPromise([] as List<T>)
        }

        CompletableFuture<T>[] futures = promises.collect { Promise<T> p ->
            (p as PromiseImpl<T>).toCompletableFuture()
        }.toArray(new CompletableFuture[0]) as CompletableFuture<T>[]

        CompletableFuture<List<T>> combined = CompletableFuture.allOf(futures).thenApply({ Void v ->
            return futures.collect { CompletableFuture<T> f -> f.join() } as List<T>
        })

        return new PromiseImpl<List<T>>(combined)
    }

    /**
     * Return the result of whichever promise resolves first.
     * Errors from individual promises are ignored unless ALL fail.
     */
    static <T> Promise<T> any(List<Promise<T>> promises) {
        if (!promises) {
            throw new IllegalArgumentException("Promises.any() requires at least one promise")
        }

        CompletableFuture<T>[] futures = promises.collect { Promise<T> p ->
            (p as PromiseImpl<T>).toCompletableFuture()
        }.toArray(new CompletableFuture[0]) as CompletableFuture<T>[]

        CompletableFuture<Object> anyOf = CompletableFuture.anyOf(futures)
        CompletableFuture<T> typed = anyOf.thenApply({ Object result -> (T) result })
        return new PromiseImpl<T>(typed)
    }

    /**
     * Create a promise that resolves after a delay.
     * Useful for retry back-off, test synchronisation.
     */
    static Promise<Void> delay(long millis) {
        return async({
            Thread.sleep(millis)
            return null
        } as Callable<Void>)
    }

    /**
     * Wrap a Callable with a timeout — fails with RuntimeException if the
     * callable does not complete within the given duration.
     */
    static <T> Promise<T> withTimeout(long timeout, TimeUnit unit, Callable<T> task) {
        Promise<T> p = async(task)
        return async({
            return p.get(timeout, unit)
        } as Callable<T>)
    }
}
