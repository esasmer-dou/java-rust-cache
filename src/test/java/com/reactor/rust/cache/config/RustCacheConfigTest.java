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
    }

    @Test
    void rejectsInvalidPort() {
        Properties properties = new Properties();
        properties.setProperty("reactor.cache.redis.port", "0");

        assertThrows(IllegalArgumentException.class, () -> RustCacheConfig.fromProperties(properties));
    }
}
