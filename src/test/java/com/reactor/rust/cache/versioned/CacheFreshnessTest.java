package com.reactor.rust.cache.versioned;

import com.reactor.rust.cache.api.CacheReadResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheFreshnessTest {
    @Test void boundsAgeAndRejectsUnknownOrFutureVersions() {
        var policy = new CacheFreshness(100, false);
        assertFalse(policy.stale("1000-1", 1100));
        assertTrue(policy.stale("1000-1", 1101));
        assertTrue(policy.stale("1200-1", 1100));
        assertTrue(policy.stale("legacy", 1100));
        assertFalse(CacheFreshness.DISABLED.stale("legacy", 1100));
        assertThrows(IllegalArgumentException.class, () -> new CacheFreshness(-1, false));
    }
    @Test void staleIsNotMistakenForMissingOrFresh() {
        assertFalse(CacheReadResult.stale().hit());
        assertFalse(CacheReadResult.stale().isMiss());
        assertTrue(CacheReadResult.staleHit(new byte[]{1}).hit());
        assertTrue(CacheReadResult.staleHit(new byte[]{1}).isStale());
    }
}
