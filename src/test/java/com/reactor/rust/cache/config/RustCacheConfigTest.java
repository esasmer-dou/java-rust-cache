package com.reactor.rust.cache.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RustCacheConfigTest {

    @Test
    void readsPropertiesWithPrefix() {
        Properties properties = new Properties();
        properties.setProperty("reactor.cache.redis.host", "redis-cache");
        properties.setProperty("reactor.cache.redis.port", "6380");
        properties.setProperty("reactor.cache.redis.database", "2");
        properties.setProperty("reactor.cache.redis.read-connections", "8");
        properties.setProperty("reactor.cache.redis.write-connections", "3");

        RustCacheConfig config = RustCacheConfig.fromProperties(properties);

        assertEquals("redis-cache", config.host());
        assertEquals(6380, config.port());
        assertEquals(2, config.database());
        assertEquals(8, config.readConnections());
        assertEquals(3, config.writeConnections());
        assertEquals(RedisTopology.STANDALONE, config.topology());
        assertEquals("redis-cache:6380", config.effectiveNodes());
    }

    @Test
    void rejectsInvalidPort() {
        Properties properties = new Properties();
        properties.setProperty("reactor.cache.redis.port", "0");

        assertThrows(IllegalArgumentException.class, () -> RustCacheConfig.fromProperties(properties));
    }

    @Test
    void readsClusterTopologyProperties() {
        Properties properties = new Properties();
        properties.setProperty("reactor.cache.redis.topology", "cluster");
        properties.setProperty("reactor.cache.redis.nodes", "redis-0:6379,redis-1:6379");
        properties.setProperty("reactor.cache.redis.cluster.max-redirects", "7");
        properties.setProperty("reactor.cache.redis.topology-refresh-ms", "15000");

        RustCacheConfig config = RustCacheConfig.fromProperties(properties);

        assertEquals(RedisTopology.CLUSTER, config.topology());
        assertEquals("redis-0:6379,redis-1:6379", config.nodes());
        assertEquals(7, config.clusterMaxRedirects());
        assertEquals(15_000, config.topologyRefreshMs());
    }

    @Test
    void rejectsClusterDatabaseSelection() {
        Properties properties = new Properties();
        properties.setProperty("reactor.cache.redis.topology", "cluster");
        properties.setProperty("reactor.cache.redis.nodes", "redis-0:6379,redis-1:6379");
        properties.setProperty("reactor.cache.redis.database", "1");

        assertThrows(IllegalArgumentException.class, () -> RustCacheConfig.fromProperties(properties));
    }

    @Test
    void sentinelRequiresMasterName() {
        Properties properties = new Properties();
        properties.setProperty("reactor.cache.redis.topology", "sentinel");
        properties.setProperty("reactor.cache.redis.nodes", "sentinel-0:26379,sentinel-1:26379");

        assertThrows(IllegalArgumentException.class, () -> RustCacheConfig.fromProperties(properties));
    }

    @Test
    void readsSentinelTopologyProperties() {
        Properties properties = new Properties();
        properties.setProperty("reactor.cache.redis.topology", "sentinel");
        properties.setProperty("reactor.cache.redis.nodes", "sentinel-0:26379,sentinel-1:26379");
        properties.setProperty("reactor.cache.redis.sentinel.master-name", "mymaster");
        properties.setProperty("reactor.cache.redis.sentinel.username", "sentinel-user");
        properties.setProperty("reactor.cache.redis.sentinel.password", "sentinel-secret");
        properties.setProperty("reactor.cache.redis.sentinel.master-check-ms", "750");

        RustCacheConfig config = RustCacheConfig.fromProperties(properties);

        assertEquals(RedisTopology.SENTINEL, config.topology());
        assertEquals("sentinel-0:26379,sentinel-1:26379", config.nodes());
        assertEquals("mymaster", config.sentinelMasterName());
        assertEquals("sentinel-user", config.sentinelUsername());
        assertEquals("sentinel-secret", config.sentinelPassword());
        assertEquals(750, config.sentinelMasterCheckMs());
    }
}
