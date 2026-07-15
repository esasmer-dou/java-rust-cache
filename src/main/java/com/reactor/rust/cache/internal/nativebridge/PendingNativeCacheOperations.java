package com.reactor.rust.cache.internal.nativebridge;

import com.reactor.rust.cache.api.NativeCacheResponseHandle;
import com.reactor.rust.cache.core.RedisCacheException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class PendingNativeCacheOperations {

    private final AtomicLong callbackIds = new AtomicLong(1);
    private final ConcurrentHashMap<Long, Pending<?>> pending = new ConcurrentHashMap<>();

    PendingBytes beginBytes(int clientId) {
        long callbackId = nextCallbackId();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pending.put(callbackId, new Pending<>(clientId, future));
        return new PendingBytes(callbackId, future);
    }

    PendingNativeJson beginNativeJson(int clientId) {
        long callbackId = nextCallbackId();
        CompletableFuture<NativeCacheResponseHandle> future = new CompletableFuture<>();
        pending.put(callbackId, new Pending<>(clientId, future));
        return new PendingNativeJson(callbackId, future);
    }

    void accepted(long callbackId, CompletableFuture<?> future, int timeoutMs) {
        long guardTimeoutMs = Math.max(1L, timeoutMs) + 1_000L;
        future.orTimeout(guardTimeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> pending.remove(callbackId));
    }

    void rejected(long callbackId, CompletableFuture<?> future, String message) {
        if (pending.remove(callbackId) != null) {
            future.completeExceptionally(new RedisCacheException(message));
        }
    }

    void rejected(long callbackId, CompletableFuture<?> future, Throwable error) {
        if (pending.remove(callbackId) != null) {
            RuntimeException failure = error instanceof RuntimeException runtime
                    ? runtime
                    : new RedisCacheException("Native Redis async operation failed", error);
            future.completeExceptionally(failure);
        }
    }

    @SuppressWarnings("unchecked")
    boolean completeBytes(long callbackId, byte[] value, String errorMessage) {
        Pending<?> operation = pending.remove(callbackId);
        if (operation == null) {
            return false;
        }
        if (errorMessage == null) {
            return ((CompletableFuture<byte[]>) operation.future()).complete(value);
        }
        return operation.future().completeExceptionally(new RedisCacheException(errorMessage));
    }

    @SuppressWarnings("unchecked")
    boolean completeNativeJson(long callbackId, int nativeId, String errorMessage) {
        Pending<?> operation = pending.remove(callbackId);
        if (operation == null) {
            return false;
        }
        if (errorMessage != null) {
            return operation.future().completeExceptionally(new RedisCacheException(errorMessage));
        }
        NativeCacheResponseHandle handle = nativeId > 0
                ? new NativeCacheResponseHandle(nativeId)
                : null;
        return ((CompletableFuture<NativeCacheResponseHandle>) operation.future()).complete(handle);
    }

    void closeClient(int clientId) {
        RedisCacheException error = new RedisCacheException("Native Redis client was closed");
        for (var entry : pending.entrySet()) {
            Pending<?> operation = entry.getValue();
            if (operation.clientId() == clientId && pending.remove(entry.getKey(), operation)) {
                operation.future().completeExceptionally(error);
            }
        }
    }

    int size() {
        return pending.size();
    }

    private long nextCallbackId() {
        long callbackId = callbackIds.getAndIncrement();
        if (callbackId > 0) {
            return callbackId;
        }
        throw new IllegalStateException("Native cache callback id space exhausted");
    }

    record PendingBytes(long callbackId, CompletableFuture<byte[]> future) {}

    record PendingNativeJson(
            long callbackId,
            CompletableFuture<NativeCacheResponseHandle> future) {}

    private record Pending<T>(int clientId, CompletableFuture<T> future) {}
}
