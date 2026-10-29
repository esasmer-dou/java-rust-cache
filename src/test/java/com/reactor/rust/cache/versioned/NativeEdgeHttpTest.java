package com.reactor.rust.cache.versioned;

import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.NativeEdgePolicies;
import com.reactor.rust.bridge.RouteDef;
import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.internal.nativebridge.NativeRedisBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Dedicated JVM, freshly built edge SO and real Redis, never a mock Redis oracle. */
@EnabledIfSystemProperty(named = "reactor.test.native-edge", matches = "true")
public class NativeEdgeHttpTest {
    @Test void nativeHitsVersionsStaleAndRejectionPreserveJavaFallback() throws Exception {
        System.setProperty("reactor.rust.jni.workers", "1");
        System.setProperty("reactor.rust.runtime.worker-threads", "1");
        int redis = NativeRedisBridge.createClient(RustCacheConfig.builder()
                .host(System.getProperty("reactor.test.redis.host", "127.0.0.1"))
                .port(Integer.getInteger("reactor.test.redis.port", 6379))
                .readConnections(1).writeConnections(1).maxReadInflight(4).maxWriteInflight(1)
                .maxResponseBytes(16384).build());
        String namespace = "edge-proof-" + System.nanoTime();
        String version = System.currentTimeMillis() + "-a";
        String first = "{\"name\":\"\u00c7a\u011fr\u0131\",\"id\":1}";
        NativeRedisBridge.set(redis, namespace + ":current", ("7|" + version).getBytes(StandardCharsets.UTF_8), 30000);
        NativeRedisBridge.set(redis, namespace + ":v:" + version + ":id:1", first.getBytes(StandardCharsets.UTF_8), 30000);
        NativeRedisBridge.set(redis, namespace + ":old", "1|1000-a".getBytes(StandardCharsets.UTF_8), 30000);
        NativeRedisBridge.set(redis, namespace + ":v:1000-a:id:1", "{\"old\":true}".getBytes(StandardCharsets.UTF_8), 30000);
        NativeBridge.configureRuntimeFromProperties();
        NativeBridge.passNativeBridgeClass(Callbacks.class);
        register(redis, namespace, "/catalog/{id}", ":current", 60000, false, 0, 0);
        register(redis, namespace, "/stale/{id}", ":old", 1, true, 0, 0);
        register(redis, namespace, "/fresh/{id}", ":old", 1, false, 0, 0);
        NativeEdgePolicies.register("GET", "/limited", 0, "", "", "", 0, false, 1, 1, true);
        NativeBridge.registerRoutes(List.of(route("/catalog/{id}"), route("/stale/{id}"), route("/fresh/{id}"), route("/limited")));
        int port = NativeBridge.startHttpServerAndGetPort(0);
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            Callbacks.calls.set(0);
            for (int i = 0; i < 20; i++) {
                var response = get(http, port, "/catalog/1", false);
                assertEquals(200, response.statusCode()); assertEquals(first, response.body());
                assertEquals("application/json; charset=utf-8",
                        response.headers().firstValue("Content-Type").orElseThrow());
            }
            assertEquals(0, Callbacks.calls.get(), "Edge hits must never invoke a Java handler");
            assertEquals("{\"fallback\":true}", get(http, port, "/catalog/missing", false).body());
            assertEquals("{\"fallback\":true}", get(http, port, "/catalog/1?locale=tr", false).body());
            assertEquals("{\"fallback\":true}", get(http, port, "/catalog/1", true).body());
            assertEquals(3, Callbacks.calls.get());
            assertTrue(Callbacks.daemon, "Native process-lifetime JNI workers must not hold JVM shutdown open");
            var stale = get(http, port, "/stale/1", false);
            assertEquals("true", stale.headers().firstValue("X-Cache-Stale").orElseThrow());
            assertEquals("{\"old\":true}", stale.body());
            assertEquals("{\"fallback\":true}", get(http, port, "/fresh/1", false).body());
            assertEquals(4, Callbacks.calls.get());
            assertEquals(200, get(http, port, "/limited", false).statusCode());
            int callbacksBeforeLimit = Callbacks.calls.get();
            var limited = get(http, port, "/limited", false);
            assertEquals(429, limited.statusCode());
            assertEquals("application/problem+json", limited.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(callbacksBeforeLimit, Callbacks.calls.get());
            String next = System.currentTimeMillis() + "-b";
            NativeRedisBridge.set(redis, namespace + ":v:" + next + ":id:1", "{\"new\":true}".getBytes(StandardCharsets.UTF_8), 30000);
            NativeRedisBridge.set(redis, namespace + ":current", ("8|" + next).getBytes(StandardCharsets.UTF_8), 30000);
            Thread.sleep(1100);
            assertEquals("{\"new\":true}", get(http, port, "/catalog/1", false).body());
            assertEquals(callbacksBeforeLimit, Callbacks.calls.get(), "Version rollover must remain native");
            Thread.sleep(1100);
            var wave = java.util.stream.IntStream.range(0, 64).mapToObj(i -> http.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/catalog/1"))
                            .timeout(Duration.ofSeconds(4)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))).toList();
            for (var pending : wave) {
                var response = pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(200, response.statusCode());
                assertEquals("{\"new\":true}", response.body());
            }
            assertEquals(callbacksBeforeLimit, Callbacks.calls.get(),
                    "Concurrent version/body refresh must share the native fetch, not queue Java handlers");
            assertThrows(IllegalStateException.class, () -> NativeEdgePolicies.register("GET", "/late", 0,
                    "", "", "", 0, false, 1, 1, true));
        } finally {
            assertTrue(NativeBridge.stopHttpServer()); NativeRedisBridge.closeClient(redis);
        }
    }
    private static void register(int redis, String namespace, String route, String current,
                                 long age, boolean stale, int rate, int burst) {
        NativeEdgePolicies.register("GET", route, redis, namespace + current,
                namespace + ":v:{version}:id:", "id", age, stale, rate, burst, true);
    }
    private static RouteDef route(String path) {
        return new RouteDef("GET", path, 1, "java.lang.Void", "java.lang.String", true, false, false, false, 0L, 8192L);
    }
    private static HttpResponse<String> get(HttpClient client, int port, String path, boolean credentials) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(4)).GET();
        if (credentials) request.header("Authorization", "Bearer ignored");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    public static final class Callbacks {
        static final AtomicInteger calls = new AtomicInteger();
        static volatile boolean daemon;
        public static int handleRustBodylessRequestIntoBuffer(int id, ByteBuffer out, int offset,
                                                              int capacity, String path, String query, String headers) {
            daemon = Thread.currentThread().isDaemon();
            calls.incrementAndGet(); byte[] bytes = "{\"fallback\":true}".getBytes(StandardCharsets.UTF_8);
            out.position(offset); out.put(bytes); return bytes.length;
        }
    }
}
