package com.reactor.rust.cache.core;

/**
 * Lightweight entry point for live Redis topology gates.
 *
 * <p>Running these gates through Maven/Surefire inside Docker Desktop can pause
 * Redis Sentinel long enough to trigger Sentinel TILT mode. This main reuses the
 * same test logic but avoids Surefire startup overhead while the live topology is
 * running.</p>
 */
public final class RedisTopologyLiveGateMain {

    private RedisTopologyLiveGateMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "Expected one gate: sentinel-failover, cluster-redirect, cluster-failover");
        }
        RedisTopologyGateTest test = new RedisTopologyGateTest();
        switch (args[0]) {
            case "sentinel-failover" -> {
                System.setProperty("reactor.cache.redis.sentinel-failover-gate", "true");
                test.sentinelRefreshesExistingClientAfterFailover();
            }
            case "cluster-redirect" -> {
                System.setProperty("reactor.cache.redis.cluster-redirect-gate", "true");
                test.clusterHandlesMovedAndAskRedirects();
            }
            case "cluster-failover" -> {
                System.setProperty("reactor.cache.redis.cluster-failover-gate", "true");
                test.clusterRefreshesExistingClientAfterReplicaFailover();
            }
            default -> throw new IllegalArgumentException("Unknown gate: " + args[0]);
        }
    }
}
