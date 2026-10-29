package com.reactor.rust.cache.versioned;

/** Opt-in snapshot age bound. Age is measured from snapshot construction, conservatively. */
public record CacheFreshness(long maxAgeMillis, boolean serveStale) {
    public static final CacheFreshness DISABLED = new CacheFreshness(0, false);
    public CacheFreshness {
        if (maxAgeMillis < 0) throw new IllegalArgumentException("maxAgeMillis must not be negative");
    }
    public boolean stale(String version, long nowMillis) {
        if (maxAgeMillis == 0) return false;
        long until = freshUntilMillis(version, nowMillis);
        return until == 0 || nowMillis > until;
    }

    /** Parses the timestamp once, without a temporary substring; zero denotes an invalid age. */
    long freshUntilMillis(String version, long nowMillis) {
        if (maxAgeMillis == 0) return Long.MAX_VALUE;
        int dash = version.indexOf('-');
        if (dash < 1) return 0;
        try {
            long created = Long.parseLong(version, 0, dash, 10);
            if (created <= 0 || created > nowMillis) return 0;
            return created > Long.MAX_VALUE - maxAgeMillis ? Long.MAX_VALUE : created + maxAgeMillis;
        } catch (NumberFormatException invalid) { return 0; }
    }
}
