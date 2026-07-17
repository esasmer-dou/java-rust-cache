package com.reactor.rust.cache.versioned;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionedJsonKeysTest {

    @Test
    void keepsSafeKeyPartsReadable() {
        assertEquals("customer-cache", VersionedJsonKeys.normalizeNamespace(" customer-cache "));
        assertEquals("VIP_2026.active", VersionedJsonKeys.sanitize("VIP_2026.active"));
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
