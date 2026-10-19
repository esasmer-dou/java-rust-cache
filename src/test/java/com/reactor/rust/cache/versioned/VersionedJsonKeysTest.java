package com.reactor.rust.cache.versioned;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionedJsonKeysTest {

    @Test
    void keepsSafeKeyPartsReadable() {
        assertEquals("customer-cache", VersionedJsonKeys.normalizeNamespace(" customer-cache "));
        assertEquals("VIP_2026.active", VersionedJsonKeys.sanitize("VIP_2026.active"));
    }

    @Test
    void safeAsciiIsReusedWithoutEncodingAllocation() {
        String input = new String("segment-standard_2026.active");
        assertSame(input, VersionedJsonKeys.sanitize(input));
        assertEquals("a".repeat(256), VersionedJsonKeys.sanitize("a".repeat(256)));
        assertEquals("%C3%BC".repeat(128), VersionedJsonKeys.sanitize("\u00fc".repeat(128)));
        assertThrows(IllegalArgumentException.class, () -> VersionedJsonKeys.sanitize("\u00fc".repeat(129)));
    }

    @Test
    void optimizedEncodingMatchesUtf8ReferenceForMixedInputs() {
        java.util.Random random = new java.util.Random(4217);
        for (int test = 0; test < 1000; test++) {
            StringBuilder input = new StringBuilder("key");
            for (int i = random.nextInt(32); i > 0; i--) input.append((char) random.nextInt(65536));
            String value = input.toString();
            StringBuilder expected = new StringBuilder();
            for (byte raw : value.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                int c = raw & 255;
                if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                        || c == '.' || c == '_' || c == '-') expected.append((char) c);
                else expected.append('%').append("0123456789ABCDEF".charAt(c >>> 4))
                        .append("0123456789ABCDEF".charAt(c & 15));
            }
            assertEquals(expected.toString(), VersionedJsonKeys.sanitize(value));
        }
    }

    @Test
    void percentEncodesDelimiterAndUtf8Bytes() {
        assertEquals("customer%3A1001", VersionedJsonKeys.sanitize("customer:1001"));
        assertEquals("TR%20VIP", VersionedJsonKeys.sanitize("TR VIP"));
        assertEquals("T%C3%BCrk%C3%A7e", VersionedJsonKeys.sanitize("Türkçe"));
        assertEquals("line%0Abreak", VersionedJsonKeys.sanitize("line\nbreak"));
    }

    @Test
    void rejectsBlankAndTooLongKeyParts() {
        assertThrows(IllegalArgumentException.class, () -> VersionedJsonKeys.sanitize(" "));
        assertThrows(IllegalArgumentException.class, () -> VersionedJsonKeys.sanitize("x".repeat(257)));
    }

    @Test
    void validatesVersionCacheAndBatchSize() {
        assertEquals(0L, VersionedJsonKeys.requireVersionCacheMillis(0));
        assertEquals(256, VersionedJsonKeys.requireBatchSize(256));

        assertThrows(IllegalArgumentException.class, () -> VersionedJsonKeys.requireVersionCacheMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> VersionedJsonKeys.requireBatchSize(0));
        assertThrows(IllegalArgumentException.class, () -> VersionedJsonKeys.requireBatchSize(4_097));
    }

    @Test
    void decodesFencedAndPreFencingPointers() {
        assertEquals("1700000000000-42", VersionedJsonKeys.decodeCurrentVersion("42|1700000000000-42"));
        assertEquals("1700000000000-legacy", VersionedJsonKeys.decodeCurrentVersion("1700000000000-legacy"));
        assertEquals("not-a-token|version", VersionedJsonKeys.decodeCurrentVersion("not-a-token|version"));
    }
}
