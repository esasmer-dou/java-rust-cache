package com.reactor.rust.cache.scheduler;

public record ProjectionRefreshResult(
        String projection,
        boolean published,
        boolean skippedLocked,
        String version,
        int writtenKeys) {

    public static ProjectionRefreshResult skipped(String projection) {
        return new ProjectionRefreshResult(projection, false, true, "", 0);
    }

    public static ProjectionRefreshResult published(String projection, String version, int writtenKeys) {
        return new ProjectionRefreshResult(projection, true, false, version, writtenKeys);
    }
}
