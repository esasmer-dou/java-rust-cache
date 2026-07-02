package com.reactor.rust.cache.core;

import com.reactor.rust.cache.config.RustCacheConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTopologyGateTest {

    @Test
    void clusterHandlesMovedAndAskRedirects() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("reactor.cache.redis.cluster-redirect-gate"));

        String nodes = requireProperty("reactor.cache.redis.integration.nodes");
        HostPort entrypoint = HostPort.parse(nodes.split(",")[0]);
        List<ClusterNode> masters = clusterMasters(entrypoint);
        Assumptions.assumeTrue(masters.size() >= 3, "Cluster redirect gate needs at least 3 masters");

        try (RustCache cache = RustCaches.create(clusterConfig(nodes))) {
            cache.resetMetrics();

            SlotPlan movedPlan = findSlotPlan(entrypoint, masters, "moved");
            String movedKey = movedPlan.key();
            assertEquals(false, cache.exists(movedKey));

            setSlotOwner(masters, movedPlan.slot(), movedPlan.target());
            assertTrue(cache.setString(movedKey, "moved-ok", 30_000));
            assertEquals("moved-ok", cache.getString(movedKey));

            SlotPlan askPlan = findSlotPlan(entrypoint, masters, "ask");
            String askKey = askPlan.key();
            assertEquals(false, cache.exists(askKey));

            command(askPlan.target().host(), askPlan.target().port(),
                    "CLUSTER", "SETSLOT", Integer.toString(askPlan.slot()), "IMPORTING", askPlan.source().id());
            command(askPlan.source().host(), askPlan.source().port(),
                    "CLUSTER", "SETSLOT", Integer.toString(askPlan.slot()), "MIGRATING", askPlan.target().id());

            assertTrue(cache.setString(askKey, "ask-ok", 30_000));
            setSlotOwner(masters, askPlan.slot(), askPlan.target());
            assertEquals("ask-ok", cache.getString(askKey));

            String metrics = cache.metricsJson();
            assertTrue(metricValue(metrics, "cluster_redirects") > 0, metrics);
            assertTrue(metricValue(metrics, "cluster_ask_redirects") > 0, metrics);
        }
    }

    @Test
    void sentinelRefreshesExistingClientAfterFailover() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("reactor.cache.redis.sentinel-failover-gate"));

        String nodes = requireProperty("reactor.cache.redis.integration.nodes");
        String masterName = requireProperty("reactor.cache.redis.integration.sentinel.master-name");
        HostPort sentinel = HostPort.parse(nodes.split(",")[0]);

        try (RustCache cache = RustCaches.create(sentinelConfig(nodes, masterName))) {
            cache.resetMetrics();
            String before = "sentinel:before:" + UUID.randomUUID();
            assertTrue(cache.setString(before, "before-ok", 30_000));
            assertEquals("before-ok", cache.getString(before));

            HostPort firstMaster = sentinelMaster(sentinel, masterName);
            command(sentinel.host(), sentinel.port(), "SENTINEL", "FAILOVER", masterName);
            HostPort nextMaster = waitForNewMaster(sentinel, masterName, firstMaster, Duration.ofSeconds(45));
            assertNotEquals(firstMaster, nextMaster);

            String after = "sentinel:after:" + UUID.randomUUID();
            RuntimeException last = null;
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            while (Instant.now().isBefore(deadline)) {
                try {
                    assertTrue(cache.setString(after, "after-ok", 30_000));
                    assertEquals("after-ok", cache.getString(after));
                    String metrics = cache.metricsJson();
                    assertTrue(metricValue(metrics, "sentinel_refreshes") > 0, metrics);
                    return;
                } catch (RuntimeException e) {
                    last = e;
                    Thread.sleep(500);
                }
            }
            if (last != null) {
                throw last;
            }
        }
    }

    @Test
    void clusterRefreshesExistingClientAfterReplicaFailover() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("reactor.cache.redis.cluster-failover-gate"));

        String nodes = requireProperty("reactor.cache.redis.integration.nodes");
        HostPort entrypoint = HostPort.parse(nodes.split(",")[0]);
        List<ClusterNode> masters = clusterMasters(entrypoint);
        List<ClusterReplica> replicas = clusterReplicas(entrypoint);
        Assumptions.assumeTrue(masters.size() >= 3, "Cluster failover gate needs at least 3 masters");
        Assumptions.assumeTrue(!replicas.isEmpty(), "Cluster failover gate needs replicas");

        try (RustCache cache = RustCaches.create(clusterConfig(nodes))) {
            cache.resetMetrics();
            SlotPlan plan = findSlotPlanWithReplica(entrypoint, masters, replicas);
            ClusterReplica replica = replicas.stream()
                    .filter(item -> item.masterId().equals(plan.source().id()))
                    .findFirst()
                    .orElseThrow();

            assertTrue(cache.setString(plan.key(), "before-failover", 30_000));
            assertEquals("before-failover", cache.getString(plan.key()));

            command(replica.host(), replica.port(), "CLUSTER", "FAILOVER", "FORCE");
            ClusterNode promoted = waitForSlotOwnerChange(entrypoint, plan.slot(), plan.source(), Duration.ofSeconds(45));
            assertEquals(replica.id(), promoted.id());

            RuntimeException last = null;
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            while (Instant.now().isBefore(deadline)) {
                try {
                    assertTrue(cache.setString(plan.key(), "after-failover", 30_000));
                    assertEquals("after-failover", cache.getString(plan.key()));
                    String metrics = cache.metricsJson();
                    assertTrue(metricValue(metrics, "cluster_refreshes") > 0, metrics);
                    return;
                } catch (RuntimeException e) {
                    last = e;
                    Thread.sleep(500);
                }
            }
            if (last != null) {
                throw last;
            }
        }
    }

    private static RustCacheConfig clusterConfig(String nodes) {
        return RustCacheConfig.builder()
                .topology("cluster")
                .nodes(nodes)
                .readConnections(Integer.getInteger("reactor.cache.redis.integration.read-connections", 2))
                .writeConnections(Integer.getInteger("reactor.cache.redis.integration.write-connections", 2))
                .maxReadInflight(Integer.getInteger("reactor.cache.redis.integration.max-read-inflight", 32))
                .maxWriteInflight(Integer.getInteger("reactor.cache.redis.integration.max-write-inflight", 32))
                .clusterMaxRedirects(Integer.getInteger("reactor.cache.redis.integration.cluster.max-redirects", 8))
                .topologyRefreshMs(Integer.getInteger("reactor.cache.redis.integration.topology-refresh-ms", 60_000))
                .build();
    }

    private static RustCacheConfig sentinelConfig(String nodes, String masterName) {
        return RustCacheConfig.builder()
                .topology("sentinel")
                .nodes(nodes)
                .sentinelMasterName(masterName)
                .readConnections(Integer.getInteger("reactor.cache.redis.integration.read-connections", 2))
                .writeConnections(Integer.getInteger("reactor.cache.redis.integration.write-connections", 2))
                .maxReadInflight(Integer.getInteger("reactor.cache.redis.integration.max-read-inflight", 32))
                .maxWriteInflight(Integer.getInteger("reactor.cache.redis.integration.max-write-inflight", 32))
                .build();
    }

    private static String requireProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must be configured");
        }
        return value.trim();
    }

    private static long metricValue(String json, String name) {
        String marker = "\"" + name + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("Metric not found: " + name + " in " + json);
        }
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            throw new AssertionError("Metric is not numeric: " + name + " in " + json);
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static SlotPlan findSlotPlan(HostPort entrypoint, List<ClusterNode> masters, String prefix) throws IOException {
        for (int attempt = 0; attempt < 256; attempt++) {
            String candidate = prefix + ":{" + UUID.randomUUID() + "-" + attempt + "}";
            int slot = keySlot(entrypoint, candidate);
            ClusterNode source = ownerForSlot(entrypoint, slot);
            ClusterNode target = masters.stream()
                    .filter(node -> !node.id().equals(source.id()))
                    .findFirst()
                    .orElseThrow();
            return new SlotPlan(slot, candidate, source, target);
        }
        throw new IllegalStateException("No cluster slot plan found");
    }

    private static SlotPlan findSlotPlanWithReplica(
            HostPort entrypoint,
            List<ClusterNode> masters,
            List<ClusterReplica> replicas) throws IOException {
        for (int attempt = 0; attempt < 512; attempt++) {
            String candidate = "failover:{" + UUID.randomUUID() + "-" + attempt + "}";
            int slot = keySlot(entrypoint, candidate);
            ClusterNode source = ownerForSlot(entrypoint, slot);
            boolean hasReplica = replicas.stream().anyMatch(replica -> replica.masterId().equals(source.id()));
            if (!hasReplica) {
                continue;
            }
            ClusterNode target = masters.stream()
                    .filter(node -> !node.id().equals(source.id()))
                    .findFirst()
                    .orElseThrow();
            return new SlotPlan(slot, candidate, source, target);
        }
        throw new IllegalStateException("No replicated cluster slot plan found");
    }

    private static int keySlot(HostPort node, String key) throws IOException {
        Object response = command(node.host(), node.port(), "CLUSTER", "KEYSLOT", key);
        return ((Long) response).intValue();
    }

    private static List<ClusterNode> clusterMasters(HostPort entrypoint) throws IOException {
        Object response = command(entrypoint.host(), entrypoint.port(), "CLUSTER", "NODES");
        String body = (String) response;
        List<ClusterNode> out = new ArrayList<>();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            String flags = parts[2];
            if (!flags.contains("master") || flags.contains("fail")) {
                continue;
            }
            HostPort address = HostPort.parse(parts[1].split("@")[0]);
            out.add(new ClusterNode(parts[0], address.host(), address.port()));
        }
        return out;
    }

    private static List<ClusterReplica> clusterReplicas(HostPort entrypoint) throws IOException {
        Object response = command(entrypoint.host(), entrypoint.port(), "CLUSTER", "NODES");
        String body = (String) response;
        List<ClusterReplica> out = new ArrayList<>();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            String flags = parts[2];
            if ((!flags.contains("slave") && !flags.contains("replica")) || flags.contains("fail")) {
                continue;
            }
            HostPort address = HostPort.parse(parts[1].split("@")[0]);
            out.add(new ClusterReplica(parts[0], address.host(), address.port(), parts[3]));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static ClusterNode ownerForSlot(HostPort entrypoint, int slot) throws IOException {
        List<Object> ranges = (List<Object>) command(entrypoint.host(), entrypoint.port(), "CLUSTER", "SLOTS");
        for (Object rangeObject : ranges) {
            List<Object> range = (List<Object>) rangeObject;
            long start = (Long) range.get(0);
            long end = (Long) range.get(1);
            if (slot < start || slot > end) {
                continue;
            }
            List<Object> master = (List<Object>) range.get(2);
            String host = (String) master.get(0);
            int port = ((Long) master.get(1)).intValue();
            String id = master.size() > 2 ? (String) master.get(2) : (String) command(host, port, "CLUSTER", "MYID");
            return new ClusterNode(id, host, port);
        }
        throw new IllegalStateException("No owner found for slot " + slot);
    }

    private static void setSlotOwner(List<ClusterNode> masters, int slot, ClusterNode owner) throws IOException {
        for (ClusterNode node : masters) {
            command(node.host(), node.port(), "CLUSTER", "SETSLOT", Integer.toString(slot), "NODE", owner.id());
        }
    }

    private static ClusterNode waitForSlotOwnerChange(
            HostPort entrypoint,
            int slot,
            ClusterNode previous,
            Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ClusterNode owner = ownerForSlot(entrypoint, slot);
            if (!owner.id().equals(previous.id())) {
                return owner;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Cluster slot owner did not change before timeout");
    }

    @SuppressWarnings("unchecked")
    private static HostPort sentinelMaster(HostPort sentinel, String masterName) throws IOException {
        Object response = command(sentinel.host(), sentinel.port(), "SENTINEL", "get-master-addr-by-name", masterName);
        List<Object> values = (List<Object>) response;
        return new HostPort((String) values.get(0), Integer.parseInt((String) values.get(1)));
    }

    private static HostPort waitForNewMaster(
            HostPort sentinel,
            String masterName,
            HostPort previous,
            Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            HostPort current = sentinelMaster(sentinel, masterName);
            if (!current.equals(previous)) {
                return current;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Sentinel did not promote a new master before timeout");
    }

    private static Object command(String host, int port, String... args) throws IOException {
        try (RespConnection connection = new RespConnection(host, port)) {
            return connection.command(args);
        }
    }

    private record HostPort(String host, int port) {
        static HostPort parse(String raw) {
            String clean = raw.trim();
            int index = clean.lastIndexOf(':');
            if (index <= 0 || index == clean.length() - 1) {
                throw new IllegalArgumentException("Expected host:port, got " + raw);
            }
            return new HostPort(clean.substring(0, index), Integer.parseInt(clean.substring(index + 1)));
        }
    }

    private record ClusterNode(String id, String host, int port) {}

    private record ClusterReplica(String id, String host, int port, String masterId) {}

    private record SlotPlan(int slot, String key, ClusterNode source, ClusterNode target) {}

    private static final class RespConnection implements AutoCloseable {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;

        private RespConnection(String host, int port) throws IOException {
            this.socket = new Socket();
            this.socket.connect(new InetSocketAddress(host, port), 2_000);
            this.socket.setSoTimeout(5_000);
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
        }

        Object command(String... args) throws IOException {
            writeCommand(args);
            Object response = read();
            if (response instanceof RedisErrorResponse error) {
                throw new IOException("Redis error: " + error.message());
            }
            return response;
        }

        private void writeCommand(String[] args) throws IOException {
            output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String arg : args) {
                byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
                output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes);
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            output.flush();
        }

        private Object read() throws IOException {
            int prefix = input.read();
            if (prefix < 0) {
                throw new IOException("Unexpected EOF");
            }
            return switch (prefix) {
                case '+' -> readLine();
                case '-' -> new RedisErrorResponse(readLine());
                case ':' -> Long.parseLong(readLine());
                case '$' -> readBulk();
                case '*' -> readArray();
                default -> throw new IOException("Unsupported RESP prefix: " + (char) prefix);
            };
        }

        private String readBulk() throws IOException {
            int len = Integer.parseInt(readLine());
            if (len < 0) {
                return null;
            }
            byte[] bytes = input.readNBytes(len);
            expectCrLf();
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private List<Object> readArray() throws IOException {
            int len = Integer.parseInt(readLine());
            if (len < 0) {
                return List.of();
            }
            List<Object> items = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                items.add(read());
            }
            return items;
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64);
            int previous = -1;
            while (true) {
                int current = input.read();
                if (current < 0) {
                    throw new IOException("Unexpected EOF while reading line");
                }
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = out.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
                }
                out.write(current);
                previous = current;
            }
        }

        private void expectCrLf() throws IOException {
            int cr = input.read();
            int lf = input.read();
            if (cr != '\r' || lf != '\n') {
                throw new IOException(String.format(Locale.ROOT, "Invalid CRLF: %s/%s", cr, lf));
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private record RedisErrorResponse(String message) {}
}
