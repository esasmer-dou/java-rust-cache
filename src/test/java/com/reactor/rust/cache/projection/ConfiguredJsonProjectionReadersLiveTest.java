package com.reactor.rust.cache.projection;

import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.core.RustCaches;
import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.api.CacheReadSnapshot;
import com.reactor.rust.cache.versioned.CacheFreshness;
import com.reactor.rust.cache.versioned.VersionedL1Cache;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "reactor.cache.redis.integration", matches = "true")
class ConfiguredJsonProjectionReadersLiveTest {
    private static final Pattern CALLS = Pattern.compile("\"calls\":(\\d+)");

    @Test
    void mixedReadersUseRealRedisButRepeatedL1ReadsAvoidNativeCallsAndCloseOnlyOwnedStorage() {
        var config = RustCacheConfig.builder()
                .host(System.getProperty("reactor.cache.redis.integration.host", "127.0.0.1"))
                .port(Integer.getInteger("reactor.cache.redis.integration.port", 6379)).build();
        String ns = "l1-it-" + UUID.randomUUID();
        String version = System.currentTimeMillis() + "-1";
        byte[] body = "{\"name\":\"\u0130pek \u015eahin\",\"nested\":{\"city\":\"\u0130stanbul\"}}"
                .getBytes(StandardCharsets.UTF_8);
        try (var cache = RustCaches.create(config)) {
            cache.setString(ns + ":current", version, 60000);
            cache.setBytes(ns + ":v:" + version + ":id:1", body, 60000);
            cache.setBytes(ns + ":v:" + version + ":idx:segment:standard", body, 60000);
            var base = new CacheReaderProjectionSettings("detail", ns, CacheFreshness.DISABLED);
            var remote = new CacheReaderProjectionSettings("remote", ns, CacheFreshness.DISABLED);
            var options = new VersionedL1Cache.Options(4, 128, 32, 60000, 16384);
            try (var readers = ConfiguredJsonProjectionReaders.create(cache,
                    List.of(new L1ProjectionSettings(base, options), new L1ProjectionSettings(remote, null)), 60000)) {
                var detail = readers.bind(() -> "detail");
                var segment = detail.bind(() -> "segment");
                assertArrayEquals(body, detail.getById(1).bytes());
                assertArrayEquals(body, segment.get("standard").bytes());
                long calls = calls(cache.metricsJson());
                for (int i = 0; i < 1000; i++) {
                    byte[] copy = detail.getById(1).bytes();
                    assertArrayEquals(body, copy); copy[0] = 0;
                    assertArrayEquals(body, segment.get("standard").bytes());
                }
                assertEquals(calls, calls(cache.metricsJson()), "L1 hits must not cross JNI into Redis");
                assertTrue(readers.metricsJson(cache).contains("\"enabled_projections\":1"));
                assertTrue(readers.metricsJson(cache).contains("\"hits\":2000"));
                assertTrue(readers.bind(() -> "optional-missing").getMeta().isCacheNotReady());
                var redisOnly = readers.bind(() -> "remote");
                assertArrayEquals(body, redisOnly.getById(1).bytes());
                long first = calls(cache.metricsJson());
                assertArrayEquals(body, redisOnly.getById(1).bytes());
                assertEquals(first + 1, calls(cache.metricsJson()), "Disabled projection still fetches Redis payload");
                assertInstanceOf(CacheReadResult.class, redisOnly.readById(1));
                assertInstanceOf(CacheReadResult.class, redisOnly.bind(() -> "segment").read("standard"));
                try (var selected = assertInstanceOf(CacheReadSnapshot.class, detail.readById(1))) {
                    assertEquals(1, readers.metricsJson(cache).contains("\"active_borrows\":1") ? 1 : 0);
                    assertEquals(body.length, selected.length());
                }
                try (var selected = assertInstanceOf(CacheReadSnapshot.class, segment.read("standard"))) {
                    assertEquals(body.length, selected.length());
                }
                assertTrue(readers.metricsJson(cache).contains("\"active_borrows\":0"));
                readers.close();
                assertThrows(IllegalStateException.class, () -> detail.getById(1));
                assertEquals(version, cache.getString(ns + ":current"), "Reader must not close shared Redis client");
            }
        }
    }

    @Test
    void disabledL1HasNoSlabAndAnObservedNewVersionReplacesIndexPayload() throws Exception {
        var config = RustCacheConfig.builder()
                .host(System.getProperty("reactor.cache.redis.integration.host", "127.0.0.1"))
                .port(Integer.getInteger("reactor.cache.redis.integration.port", 6379)).build();
        String ns = "l1-version-it-" + UUID.randomUUID();
        var base = new CacheReaderProjectionSettings("segment", ns, CacheFreshness.DISABLED);
        try (var cache = RustCaches.create(config);
             var disabled = ConfiguredJsonProjectionReaders.create(cache, List.of(new L1ProjectionSettings(base, null)), 10)) {
            assertTrue(disabled.metricsJson(cache).contains("\"slab_bytes\":0"));
            var options = new VersionedL1Cache.Options(2, 64, 32, 60000, 16384);
            try (var enabled = ConfiguredJsonProjectionReaders.create(cache, List.of(new L1ProjectionSettings(base, options)), 10)) {
                var index = enabled.bind(() -> "segment").bind(() -> "segment");
                assertTrue(index.get("standard").isCacheNotReady());
                cache.setBytes(ns + ":v:1000-1:idx:segment:standard", new byte[]{1}, 60000);
                cache.setString(ns + ":current", "1|1000-1", 60000);
                assertArrayEquals(new byte[]{1}, index.get("standard").bytes());
                cache.setBytes(ns + ":v:2000-2:idx:segment:standard", new byte[]{2}, 60000);
                cache.setString(ns + ":current", "2|2000-2", 60000);
                Thread.sleep(30);
                assertArrayEquals(new byte[]{2}, index.get("standard").bytes());
                assertTrue(index.get("unknown").isMiss());
            }
        }
    }

    private static long calls(String metrics) {
        var matcher = CALLS.matcher(metrics);
        assertTrue(matcher.find(), metrics);
        return Long.parseLong(matcher.group(1));
    }
}
