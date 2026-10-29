package com.reactor.rust.cache.api;

import java.nio.ByteBuffer;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/** Transferable immutable payload ownership. No raw array/buffer escapes; close after consumption. */
public abstract non-sealed class CacheReadSnapshot implements CacheReadValue, AutoCloseable {
    private static final VarHandle STATE;
    static {
        try {
            STATE = MethodHandles.lookup().findVarHandle(CacheReadSnapshot.class, "state", int.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
    private volatile int state; // open, writing, closed, close-after-write; no separate atomic object
    private final CacheReadResult.Status status;
    private final String version;
    private final int length;

    protected CacheReadSnapshot(CacheReadResult.Status status, String version, int length) {
        this.status = Objects.requireNonNull(status, "status");
        if (length < 0) throw new IllegalArgumentException("Negative snapshot length");
        this.version = version;
        this.length = length;
    }

    public final CacheReadResult.Status status() { return status; }
    public final String version() { return version; }
    public final int length() { return length; }
    public final boolean hit() { return status == CacheReadResult.Status.HIT || status == CacheReadResult.Status.STALE_HIT; }

    /** Copies into the response buffer, not into an intermediate payload array. */
    public final int writeTo(ByteBuffer out, int offset) {
        if (!STATE.compareAndSet(this, 0, 1)) throw new IllegalStateException("Snapshot is closed or already being written");
        try {
            Objects.checkFromIndexSize(offset, 0, out.capacity());
            if (length > out.capacity() - offset) return -length;
            copyTo(out, offset);
            return length;
        } finally {
            if (!STATE.compareAndSet(this, 1, 0)) { STATE.setVolatile(this, 2); release(); }
        }
    }

    protected abstract void copyTo(ByteBuffer out, int offset);
    protected abstract void release();

    @Override public final void close() {
        for (;;) {
            int current = (int) STATE.getVolatile(this);
            if (current >= 2) return;
            if (STATE.compareAndSet(this, current, current == 0 ? 2 : 3)) {
                if (current == 0) release();
                return;
            }
        }
    }

    /** Transfers ownership of a fresh result. The caller must not subsequently mutate its bytes. */
    public static CacheReadSnapshot takeOwnership(CacheReadResult result) {
        return takeOwnership(result, null);
    }

    /** Transfers a fresh result together with a known version; remote readers may leave it null. */
    public static CacheReadSnapshot takeOwnership(CacheReadResult result, String version) {
        return new Heap(result, version);
    }

    private static final class Heap extends CacheReadSnapshot {
        private byte[] bytes;
        Heap(CacheReadResult result, String version) {
            super(result.status(), version, result.hit() ? result.bytes().length : 0);
            bytes = result.hit() ? result.bytes() : null;
        }
        protected void copyTo(ByteBuffer out, int offset) {
            if (bytes != null) out.put(offset, bytes, 0, bytes.length);
        }
        protected void release() { bytes = null; }
    }
}
