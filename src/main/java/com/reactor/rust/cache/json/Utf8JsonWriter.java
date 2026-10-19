package com.reactor.rust.cache.json;

import java.util.List;

/** Opt-in byte writer; the original StringBuilder JsonWriter remains source compatible. */
public abstract class Utf8JsonWriter {
    private final int maxBytes;
    protected Utf8JsonWriter() { this(1_048_576); }
    protected Utf8JsonWriter(int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
    }
    protected final Utf8JsonBuffer json(int capacity) { return new Utf8JsonBuffer(Math.min(capacity, maxBytes), maxBytes); }
    protected final byte[] utf8(Utf8JsonBuffer out) { return out.toByteArray(); }
    protected final Utf8JsonBuffer field(Utf8JsonBuffer out, String name, long value) { return quote(out, name).append(':').append(value); }
    protected final Utf8JsonBuffer field(Utf8JsonBuffer out, String name, boolean value) { return quote(out, name).append(':').append(value); }
    protected final Utf8JsonBuffer stringField(Utf8JsonBuffer out, String name, String value) { return quote(quote(out, name).append(':'), value); }
    protected final Utf8JsonBuffer stringArrayField(Utf8JsonBuffer out, String name, List<String> values) { return stringArray(quote(out, name).append(':'), values); }
    protected final Utf8JsonBuffer stringArray(Utf8JsonBuffer out, List<String> values) {
        out.append('[');
        for (int i = 0; i < values.size(); i++) { if (i != 0) out.append(','); quote(out, values.get(i)); }
        return out.append(']');
    }
    protected final Utf8JsonBuffer quote(Utf8JsonBuffer out, String value) {
        out.append('"');
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c >= 32 && c != '"' && c != '\\') { out.append(c); continue; }
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> out.append("\\u00").append(Character.forDigit(c >>> 4, 16))
                            .append(Character.forDigit(c & 15, 16));
                }
            }
        }
        return out.append('"');
    }
}
