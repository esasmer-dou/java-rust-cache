package com.reactor.rust.cache.versioned;

/** Opt-in snapshot age bound. Age is measured from snapshot construction, conservatively. */
public record CacheFreshness(long maxAgeMillis, boolean serveStale) {
    public static final CacheFreshness DISABLED = new CacheFreshness(0, false);
    public CacheFreshness {
        if (maxAgeMillis < 0) throw new IllegalArgumentException("maxAgeMillis must not be negative");
    }
    public boolean stale(String version, long nowMillis) {
        if (maxAgeMillis == 0) return false;
        int dash = version.indexOf('-');
        if (dash < 1) return true;
        try {
            long created = Long.parseLong(version.substring(0, dash));
            return created <= 0 || created > nowMillis || nowMillis - created > maxAgeMillis;
        } catch (NumberFormatException invalid) { return true; }
    }
}
