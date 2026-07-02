package com.reactor.rust.cache.core;

import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.lock.CacheLock;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustCacheNativeIntegrationTest {

    @Test
    void exercisesNativeRedisOperations() {
        Assumptions.assumeTrue(Boolean.getBoolean("reactor.cache.redis.integration"));

        try (RustCache cache = RustCaches.create(integrationConfig())) {
            cache.resetMetrics();
            String prefix = "it:" + UUID.randomUUID();

            String key = prefix + ":one";
            assertTrue(cache.setString(key, "hello", 30_000));
            assertEquals("hello", cache.getString(key));
            assertTrue(cache.exists(key));
            assertTrue(cache.ttlMillis(key) > 0);

            assertTrue(cache.setBytesNx(prefix + ":nx", bytes("created"), 30_000));
            assertFalse(cache.setBytesNx(prefix + ":nx", bytes("ignored"), 30_000));
            assertEquals("created", cache.getString(prefix + ":nx"));

            String[] keys = {prefix + ":a", prefix + ":b", prefix + ":missing"};
            byte[][] values = {bytes("A"), bytes("B")};
            assertEquals(2, cache.setManyBytes(new String[]{keys[0], keys[1]}, values, 30_000));

            byte[][] found = cache.mgetBytes(keys);
            assertArrayEquals(bytes("A"), found[0]);
            assertArrayEquals(bytes("B"), found[1]);
            assertNull(found[2]);

            assertEquals(1, cache.increment(prefix + ":counter", 30_000));
            assertTrue(cache.expire(prefix + ":counter", 30_000));
            assertTrue(cache.ttlMillis(prefix + ":counter") > 0);

            CacheLock lock = cache.locks().tryAcquire(prefix + ":lock", 30_000);
            assertNotNull(lock);
            try (lock) {
                assertTrue(lock.renew(30_000));
            }

            assertEquals(1, cache.delete(key));
            assertFalse(cache.exists(key));

            String metrics = cache.metricsJson();
            assertTrue(metrics.contains("\"calls\":"));
            assertTrue(metrics.contains("\"errors\":0"));
        }
    }

    @Test
    void reconnectsAfterRedisContainerRestart() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("reactor.cache.redis.reconnect-gate"));

        String container = System.getProperty("reactor.cache.redis.integration.container");
        Assumptions.assumeTrue(container != null && !container.isBlank());

        try (RustCache cache = RustCaches.create(integrationConfig())) {
            String prefix = "reconnect:" + UUID.randomUUID();
            assertTrue(cache.setString(prefix + ":before", "ok", 30_000));
            assertEquals("ok", cache.getString(prefix + ":before"));

            runDocker("restart", "-t", "1", container);

            Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
            RuntimeException lastError = null;
            while (Instant.now().isBefore(deadline)) {
                try {
                    assertTrue(cache.setString(prefix + ":after", "ok", 30_000));
                    assertEquals("ok", cache.getString(prefix + ":after"));
                    return;
                } catch (RuntimeException e) {
                    lastError = e;
                    Thread.sleep(250);
                }
            }
            if (lastError != null) {
                throw lastError;
            }
            throw new AssertionError("Redis reconnect gate did not complete before deadline");
        }
    }

    @Test
    void shortLoadSoakStaysErrorFree() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("reactor.cache.redis.load-gate"));

        int threads = Integer.getInteger("reactor.cache.redis.load-gate.threads", 8);
        int operationsPerThread = Integer.getInteger("reactor.cache.redis.load-gate.operations-per-thread", 500);
        String prefix = "load:" + UUID.randomUUID();

        try (RustCache cache = RustCaches.create(integrationConfig())) {
            cache.resetMetrics();
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>(threads);

            for (int thread = 0; thread < threads; thread++) {
                int threadId = thread;
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            String key = prefix + ":" + threadId + ":" + i;
                            if (!cache.setString(key, "v" + i, 30_000)) {
                                errors.incrementAndGet();
                            }
                            if (cache.getString(key) == null) {
                                errors.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            assertEquals(0, errors.get());
            assertTrue(cache.metricsJson().contains("\"errors\":0"));
        }
    }

    private static RustCacheConfig integrationConfig() {
        RustCacheConfig.Builder builder = RustCacheConfig.builder()
                .topology(System.getProperty("reactor.cache.redis.integration.topology", "standalone"))
                .host(System.getProperty("reactor.cache.redis.integration.host", "127.0.0.1"))
                .port(Integer.getInteger("reactor.cache.redis.integration.port", 6379))
                .database(Integer.getInteger("reactor.cache.redis.integration.database", 0))
                .readConnections(Integer.getInteger("reactor.cache.redis.integration.read-connections", 2))
                .writeConnections(Integer.getInteger("reactor.cache.redis.integration.write-connections", 2))
                .maxReadInflight(Integer.getInteger("reactor.cache.redis.integration.max-read-inflight", 16))
                .maxWriteInflight(Integer.getInteger("reactor.cache.redis.integration.max-write-inflight", 16));
        String nodes = System.getProperty("reactor.cache.redis.integration.nodes");
        if (nodes != null && !nodes.isBlank()) {
            builder.nodes(nodes);
        }
        String sentinelMasterName = System.getProperty("reactor.cache.redis.integration.sentinel.master-name");
        if (sentinelMasterName != null && !sentinelMasterName.isBlank()) {
            builder.sentinelMasterName(sentinelMasterName);
        }
        builder.clusterMaxRedirects(Integer.getInteger("reactor.cache.redis.integration.cluster.max-redirects", 5));
        builder.topologyRefreshMs(Integer.getInteger("reactor.cache.redis.integration.topology-refresh-ms", 30_000));
        return builder.build();
    }

    private static void runDocker(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add("docker");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).inheritIO().start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("docker command failed with exit " + exit + ": " + command);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
