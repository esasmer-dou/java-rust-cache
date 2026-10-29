package com.reactor.rust.cache.api;

/** A cache read whose representation is selected by the configured backend. */
public sealed interface CacheReadValue permits CacheReadResult, CacheReadSnapshot {
    CacheReadResult.Status status();
    boolean hit();
}
