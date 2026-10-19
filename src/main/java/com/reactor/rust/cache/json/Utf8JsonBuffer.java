package com.reactor.rust.cache.json;

import java.util.Arrays;

/** Single-use bounded UTF-8 output. No retained ThreadLocal or full UTF-16 JSON copy. */
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
        for (int i = start; i < end; i++) append(value.charAt(i));
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
    public byte[] toByteArray() {
        if (pendingHigh != 0) { pendingHigh = 0; write('?'); }
        return Arrays.copyOf(bytes, size);
    }
    private void write(int value) { ensure(1); bytes[size++] = (byte) value; }
    private void ensure(int count) {
        if (count > limit - size) throw new IllegalStateException("JSON exceeds configured maxBytes=" + limit);
        int required = size + count;
        if (required > bytes.length)
            bytes = Arrays.copyOf(bytes, (int) Math.min(limit, Math.max((long) required, bytes.length * 2L)));
    }
}
