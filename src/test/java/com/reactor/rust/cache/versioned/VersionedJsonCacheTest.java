package com.reactor.rust.cache.versioned;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionedJsonCacheTest {

    @Test
    void keepsSafeKeyPartsReadable() {
        assertEquals("customer-cache", VersionedJsonCache.normalizeNamespace(" customer-cache "));
        assertEquals("VIP_2026.active", VersionedJsonCache.sanitize("VIP_2026.active"));
    }

    @Test
    void percentEncodesDelimiterAndUtf8Bytes() {
        assertEquals("customer%3A1001", VersionedJsonCache.sanitize("customer:1001"));
        assertEquals("TR%20VIP", VersionedJsonCache.sanitize("TR VIP"));
        assertEquals("T%C3%BCrk%C3%A7e", VersionedJsonCache.sanitize("Türkçe"));
        assertEquals("line%0Abreak", VersionedJsonCache.sanitize("line\nbreak"));
    }

    @Test
    void rejectsBlankAndTooLongKeyParts() {
        assertThrows(IllegalArgumentException.class, () -> VersionedJsonCache.sanitize(" "));
        assertThrows(IllegalArgumentException.class, () -> VersionedJsonCache.sanitize("x".repeat(257)));
    }

    @Test
    void validatesVersionCacheAndBatchSize() {
        assertEquals(0L, VersionedJsonCache.requireVersionCacheMillis(0));
        assertEquals(256, VersionedJsonCache.requireBatchSize(256));

        assertThrows(IllegalArgumentException.class, () -> VersionedJsonCache.requireVersionCacheMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> VersionedJsonCache.requireBatchSize(0));
        assertThrows(IllegalArgumentException.class, () -> VersionedJsonCache.requireBatchSize(4_097));
    }
}
