package com.reactor.rust.cache.internal.nativebridge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingNativeCacheOperationsTest {

    @Test
    void completesByteResultAndRemovesPendingEntry() {
        PendingNativeCacheOperations operations = new PendingNativeCacheOperations();
        PendingNativeCacheOperations.PendingBytes pending = operations.beginBytes(7);

        assertTrue(operations.completeBytes(pending.callbackId(), new byte[] {1, 2}, null));
        assertArrayEquals(new byte[] {1, 2}, pending.future().join());
        assertTrue(operations.size() == 0);
    }

    @Test
    void rejectedCallbackAfterCancellationReturnsFalse() {
        PendingNativeCacheOperations operations = new PendingNativeCacheOperations();
        PendingNativeCacheOperations.PendingNativeJson pending = operations.beginNativeJson(9);
        operations.accepted(pending.callbackId(), pending.future(), 1_000);

        pending.future().cancel(false);

        assertFalse(operations.completeNativeJson(pending.callbackId(), 42, null));
        assertTrue(operations.size() == 0);
    }

    @Test
    void closingClientFailsOnlyItsPendingOperations() {
        PendingNativeCacheOperations operations = new PendingNativeCacheOperations();
        PendingNativeCacheOperations.PendingBytes closed = operations.beginBytes(11);
        PendingNativeCacheOperations.PendingBytes retained = operations.beginBytes(12);

        operations.closeClient(11);

        assertThrows(CompletionException.class, closed.future()::join);
        assertFalse(retained.future().isDone());
        assertTrue(operations.size() == 1);
    }

    @Test
    void submissionFailureRemovesPendingAndPreservesOriginalException() {
        PendingNativeCacheOperations operations = new PendingNativeCacheOperations();
        PendingNativeCacheOperations.PendingBytes pending = operations.beginBytes(13);
        IllegalStateException failure = new IllegalStateException("native submission failed");

        operations.rejected(pending.callbackId(), pending.future(), failure);

        CompletionException error = assertThrows(CompletionException.class, pending.future()::join);
        assertTrue(error.getCause() == failure);
        assertTrue(operations.size() == 0);
    }
}
