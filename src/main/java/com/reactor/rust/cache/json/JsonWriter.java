package com.reactor.rust.cache.json;

import java.nio.charset.StandardCharsets;
import java.util.List;

public abstract class JsonWriter {

    protected final StringBuilder json(int initialCapacity) {
        return new StringBuilder(Math.max(32, initialCapacity));
    }

    protected final byte[] utf8(StringBuilder json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    protected final StringBuilder field(StringBuilder json, String name, long value) {
        quote(json, name).append(':').append(value);
        return json;
    }

    protected final StringBuilder field(StringBuilder json, String name, boolean value) {
        quote(json, name).append(':').append(value);
        return json;
    }

    protected final StringBuilder stringField(StringBuilder json, String name, String value) {
        quote(json, name).append(':');
        quote(json, value == null ? "" : value);
        return json;
    }

    protected final StringBuilder stringArrayField(StringBuilder json, String name, List<String> values) {
        quote(json, name).append(':');
        stringArray(json, values);
        return json;
    }

    protected final StringBuilder stringArray(StringBuilder json, List<String> values) {
        json.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            quote(json, values.get(i));
        }
        json.append(']');
        return json;
    }

    protected final StringBuilder quote(StringBuilder json, String value) {
        String safe = value == null ? "" : value;
        json.append('"');
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            json.append('0');
                        }
                        json.append(hex);
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
        return json;
    }
}
