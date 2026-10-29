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

    @Test void freshnessDeadlineHandlesExpiryMalformedFutureAndOverflow() {
        var policy = new CacheFreshness(100, false);
        assertEquals(1100, policy.freshUntilMillis("1000-1", 1000));
        assertEquals(1100, policy.freshUntilMillis("1000-1", 1101));
        for (String invalid : new String[]{"legacy", "-1-1", "0-1", "x-1", "1200-1",
                "99999999999999999999-1"}) {
            assertEquals(0, policy.freshUntilMillis(invalid, 1100), invalid);
        }
        String nearLimit = (Long.MAX_VALUE - 50) + "-1";
        assertEquals(Long.MAX_VALUE, policy.freshUntilMillis(nearLimit, Long.MAX_VALUE));
        assertFalse(policy.stale(nearLimit, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, CacheFreshness.DISABLED.freshUntilMillis("legacy", 1000));
    }
}
