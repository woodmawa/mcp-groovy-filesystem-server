package com.softwood.mcp.promise

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function

/**
 * Promise implementation backed by CompletableFuture and virtual threads.
 *
 * Virtual thread executor is shared across all PromiseImpl instances — each task
 * gets its own virtual thread from the JVM scheduler.
 *
 * v0.0.7 — Phase 1 Foundation
 */
@Slf4j
@CompileStatic
class PromiseImpl<T> implements Promise<T> {

    /** Shared virtual-thread executor — lightweight, no pool sizing needed */
    static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor()

    private final CompletableFuture<T> future

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** Wrap an existing CompletableFuture */
    PromiseImpl(CompletableFuture<T> future) {
        this.future = future
    }

    /** Create a pre-resolved promise */
    static <T> PromiseImpl<T> resolved(T value) {
        return new PromiseImpl<T>(CompletableFuture.completedFuture(value))
    }

    /** Create a pre-failed promise */
    static <T> PromiseImpl<T> failed(Throwable error) {
        CompletableFuture<T> cf = new CompletableFuture<T>()
        cf.completeExceptionally(error)
        return new PromiseImpl<T>(cf)
    }

    // -----------------------------------------------------------------------
    // Promise<T> implementation
    // -----------------------------------------------------------------------

    @Override
    @SuppressWarnings('UnnecessaryQualifiedReference')
    <R> Promise<R> then(Function<T, R> fn) {
        CompletableFuture<R> next = future.thenApplyAsync(fn, VIRTUAL_EXECUTOR)
        return new PromiseImpl<R>(next)
    }

    @Override
    Promise<T> onComplete(Consumer<T> callback) {
        future.thenAcceptAsync(callback, VIRTUAL_EXECUTOR)
        return this
    }

    @Override
    Promise<T> onError(Consumer<Throwable> callback) {
        future.exceptionallyAsync({ Throwable t ->
            callback.accept(unwrap(t))
            return null
        } as Function<Throwable, T>, VIRTUAL_EXECUTOR)
        return this
    }

    @Override
    T get(long timeout, TimeUnit unit) {
        try {
            return future.get(timeout, unit)
        } catch (TimeoutException e) {
            throw new RuntimeException("Promise timed out after ${timeout} ${unit}", e)
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() ?: e
            throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(cause)
        } catch (CancellationException e) {
            throw new RuntimeException("Promise was cancelled", e)
        }
    }

    @Override
    T get() {
        try {
            return future.join()
        } catch (Exception e) {
            Throwable cause = unwrap(e)
            throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(cause)
        }
    }

    @Override
    boolean isDone() {
        return future.isDone()
    }

    @Override
    boolean isFailed() {
        return future.isCompletedExceptionally()
    }

    @Override
    boolean cancel() {
        return future.cancel(true)
    }

    // -----------------------------------------------------------------------
    // Package-level accessor for Promises.all / Promises.any
    // -----------------------------------------------------------------------

    CompletableFuture<T> toCompletableFuture() {
        return future
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static Throwable unwrap(Throwable t) {
        if (t instanceof ExecutionException || t instanceof java.util.concurrent.CompletionException) {
            return t.getCause() ?: t
        }
        return t
    }
}
