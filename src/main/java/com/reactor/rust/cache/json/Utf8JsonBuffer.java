package com.reactor.rust.cache.json;

import java.util.Arrays;

/** Explicitly owned bounded UTF-8 output. No retained ThreadLocal or full UTF-16 JSON copy. */
public final class Utf8JsonBuffer {
    private byte[] bytes;
    private int size;
    private char pendingHigh;
    private final int limit;

    public Utf8JsonBuffer(int initialCapacity, int maxBytes) {
        if (maxBytes < 1 || initialCapacity < 0 || initialCapacity > maxBytes)
            throw new IllegalArgumentException("initialCapacity must be 0..maxBytes and maxBytes positive");
        limit = maxBytes;
        bytes = new byte[Math.min(Math.max(32, initialCapacity), maxBytes)];
    }
    public Utf8JsonBuffer append(String value) {
        if (value == null) value = "null";
        return appendRange(value, 0, value.length());
    }
    Utf8JsonBuffer appendRange(String value, int start, int end) {
        int i = start;
        while (i < end) {
            if (pendingHigh != 0 || value.charAt(i) >= 128) {
                append(value.charAt(i++));
                continue;
            }
            int ascii = i++;
            while (i < end && value.charAt(i) < 128) i++;
            int length = i - ascii;
            ensure(length);
            int offset = size;
            for (int n = 0; n < length; n++) bytes[offset + n] = (byte) value.charAt(ascii + n);
            size += length;
        }
        return this;
    }
    public Utf8JsonBuffer append(char ch) {
        if (ch < 0x80 && pendingHigh == 0) { write(ch); return this; }
        if (pendingHigh != 0) {
            char high = pendingHigh;
            pendingHigh = 0;
            if (Character.isLowSurrogate(ch)) {
                int cp = Character.toCodePoint(high, ch);
                ensure(4);
                bytes[size++] = (byte) (0xf0 | cp >>> 18);
                bytes[size++] = (byte) (0x80 | (cp >>> 12 & 63));
                bytes[size++] = (byte) (0x80 | (cp >>> 6 & 63));
                bytes[size++] = (byte) (0x80 | (cp & 63));
                return this;
            }
            write('?');
        }
        if (Character.isHighSurrogate(ch)) { pendingHigh = ch; return this; }
        if (Character.isLowSurrogate(ch)) { write('?'); return this; }
        if (ch < 0x80) write(ch);
        else if (ch < 0x800) {
            ensure(2); bytes[size++] = (byte) (0xc0 | ch >>> 6); bytes[size++] = (byte) (0x80 | (ch & 63));
        } else {
            ensure(3); bytes[size++] = (byte) (0xe0 | ch >>> 12);
            bytes[size++] = (byte) (0x80 | (ch >>> 6 & 63)); bytes[size++] = (byte) (0x80 | (ch & 63));
        }
        return this;
    }
    public Utf8JsonBuffer append(long value) {
        if (pendingHigh != 0) { pendingHigh = 0; write('?'); }
        if (value < 0) write('-');
        // Negative domain handles Long.MIN_VALUE without overflow.
        long remaining = value > 0 ? -value : value;
        long copy = remaining;
        int digits = 1;
        while (copy <= -10) { digits++; copy /= 10; }
        ensure(digits);
        int end = size + digits;
        for (int i = end - 1; i >= size; i--) { bytes[i] = (byte) ('0' - remaining % 10); remaining /= 10; }
        size = end;
        return this;
    }
    public Utf8JsonBuffer append(boolean value) { return append(value ? "true" : "false"); }

    // Only the writer's immutable, already escaped field prefixes use this path.
    Utf8JsonBuffer writeFieldPrefix(byte[] prefix) {
        int replacement = pendingHigh == 0 ? 0 : 1;
        ensure(prefix.length + replacement);
        if (replacement != 0) { bytes[size++] = '?'; pendingHigh = 0; }
        System.arraycopy(prefix, 0, bytes, size, prefix.length);
        size += prefix.length;
        return this;
    }

    /**
     * Writes eight already encoded bytes, least-significant byte first. Intended for
     * generated, escaped JSON literals, not unchecked application strings.
     */
    public Utf8JsonBuffer writePrebakedLong(long littleEndianWord) {
        int replacement = pendingHigh == 0 ? 0 : 1;
        ensure(Long.BYTES + replacement);
        if (replacement != 0) {
            bytes[size++] = '?';
            pendingHigh = 0;
        }
        PrebakedWords.LITTLE_ENDIAN.set(bytes, size, littleEndianWord);
        size += Long.BYTES;
        return this;
    }

    // Initialized only by the explicit bulk-write API, never by the legacy path.
    private static final class PrebakedWords {
        static final java.lang.invoke.VarHandle LITTLE_ENDIAN =
                java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
                        long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);
    }

    public byte[] toByteArray() {
        if (pendingHigh != 0) { pendingHigh = 0; write('?'); }
        return Arrays.copyOf(bytes, size);
    }

    /**
     * Starts a new document and drops an oversized scratch array. The caller owns
     * this buffer exclusively; returned byte arrays from previous documents stay independent.
     */
    public Utf8JsonBuffer reset(int maxRetainedCapacity) {
        if (maxRetainedCapacity < 1 || maxRetainedCapacity > limit)
            throw new IllegalArgumentException("maxRetainedCapacity must be 1..maxBytes");
        if (bytes.length > maxRetainedCapacity) bytes = new byte[maxRetainedCapacity];
        size = 0;
        pendingHigh = 0;
        return this;
    }
    private void write(int value) { ensure(1); bytes[size++] = (byte) value; }
    private void ensure(int count) {
        if (count > limit - size) throw new IllegalStateException("JSON exceeds configured maxBytes=" + limit);
        int required = size + count;
        if (required > bytes.length)
            bytes = Arrays.copyOf(bytes, (int) Math.min(limit, Math.max((long) required, bytes.length * 2L)));
    }
}
