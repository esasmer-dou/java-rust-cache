package com.reactor.rust.cache.config;

import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public final class RustCacheConfig {

    public static final String PROPERTY_PREFIX = "reactor.cache.redis.";

    private final String host;
    private final int port;
    private final RedisTopology topology;
    private final String nodes;
    private final String sentinelMasterName;
    private final String sentinelUsername;
    private final String sentinelPassword;
    private final int sentinelMasterCheckMs;
    private final String username;
    private final String password;
    private final int database;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int writeTimeoutMs;
    private final int readConnections;
    private final int writeConnections;
    private final int maxReadInflight;
    private final int maxWriteInflight;
    private final int maxResponseBytes;
    private final int clusterMaxRedirects;
    private final int topologyRefreshMs;

    private RustCacheConfig(Builder builder) {
        this.host = requireText(builder.host, "host");
        this.port = requirePort(builder.port);
        this.topology = Objects.requireNonNull(builder.topology, "topology");
        this.nodes = normalizeOptional(builder.nodes);
        this.sentinelMasterName = normalizeOptional(builder.sentinelMasterName);
        this.sentinelUsername = normalizeOptional(builder.sentinelUsername);
        this.sentinelPassword = normalizeOptional(builder.sentinelPassword);
        this.sentinelMasterCheckMs = requirePositive(builder.sentinelMasterCheckMs, "sentinelMasterCheckMs");
        this.username = normalizeOptional(builder.username);
        this.password = normalizeOptional(builder.password);
        this.database = requireNonNegative(builder.database, "database");
        this.connectTimeoutMs = requirePositive(builder.connectTimeoutMs, "connectTimeoutMs");
        this.readTimeoutMs = requirePositive(builder.readTimeoutMs, "readTimeoutMs");
        this.writeTimeoutMs = requirePositive(builder.writeTimeoutMs, "writeTimeoutMs");
        this.readConnections = requirePositive(builder.readConnections, "readConnections");
        this.writeConnections = requirePositive(builder.writeConnections, "writeConnections");
        this.maxReadInflight = requirePositive(builder.maxReadInflight, "maxReadInflight");
        this.maxWriteInflight = requirePositive(builder.maxWriteInflight, "maxWriteInflight");
        this.maxResponseBytes = requirePositive(builder.maxResponseBytes, "maxResponseBytes");
        this.clusterMaxRedirects = requirePositive(builder.clusterMaxRedirects, "clusterMaxRedirects");
        this.topologyRefreshMs = requirePositive(builder.topologyRefreshMs, "topologyRefreshMs");
        validateTopology();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RustCacheConfig fromProperties() {
        return fromProperties(new Properties());
    }

    public static RustCacheConfig fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        Builder builder = builder();
        builder.host(readString(properties, "host", builder.host));
        builder.port(readInt(properties, "port", builder.port));
        builder.topology(RedisTopology.parse(readString(properties, "topology", builder.topology.wireValue())));
        builder.nodes(readString(properties, "nodes", builder.nodes));
        builder.sentinelMasterName(readString(properties, "sentinel.master-name", builder.sentinelMasterName));
        builder.sentinelUsername(readString(properties, "sentinel.username", builder.sentinelUsername));
        builder.sentinelPassword(readString(properties, "sentinel.password", builder.sentinelPassword));
        builder.sentinelMasterCheckMs(readInt(properties, "sentinel.master-check-ms", builder.sentinelMasterCheckMs));
        builder.username(readString(properties, "username", builder.username));
        builder.password(readString(properties, "password", builder.password));
        builder.database(readInt(properties, "database", builder.database));
        builder.connectTimeoutMs(readInt(properties, "connect-timeout-ms", builder.connectTimeoutMs));
        builder.readTimeoutMs(readInt(properties, "read-timeout-ms", builder.readTimeoutMs));
        builder.writeTimeoutMs(readInt(properties, "write-timeout-ms", builder.writeTimeoutMs));
        builder.readConnections(readInt(properties, "read-connections", builder.readConnections));
        builder.writeConnections(readInt(properties, "write-connections", builder.writeConnections));
        builder.maxReadInflight(readInt(properties, "max-read-inflight", builder.maxReadInflight));
        builder.maxWriteInflight(readInt(properties, "max-write-inflight", builder.maxWriteInflight));
        builder.maxResponseBytes(readInt(properties, "max-response-bytes", builder.maxResponseBytes));
        builder.clusterMaxRedirects(readInt(properties, "cluster.max-redirects", builder.clusterMaxRedirects));
        builder.topologyRefreshMs(readInt(properties, "topology-refresh-ms", builder.topologyRefreshMs));
        return builder.build();
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public RedisTopology topology() {
        return topology;
    }

    public String nodes() {
        return nodes;
    }

    public String sentinelMasterName() {
        return sentinelMasterName;
    }

    public String sentinelUsername() {
        return sentinelUsername;
    }

    public String sentinelPassword() {
        return sentinelPassword;
    }

    public int sentinelMasterCheckMs() {
        return sentinelMasterCheckMs;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public int database() {
        return database;
    }

    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    public int writeTimeoutMs() {
        return writeTimeoutMs;
    }

    public int readConnections() {
        return readConnections;
    }

    public int writeConnections() {
        return writeConnections;
    }

    public int maxReadInflight() {
        return maxReadInflight;
    }

    public int maxWriteInflight() {
        return maxWriteInflight;
    }

    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    public int clusterMaxRedirects() {
        return clusterMaxRedirects;
    }

    public int topologyRefreshMs() {
        return topologyRefreshMs;
    }

    public String effectiveNodes() {
        return nodes.isEmpty() ? host + ":" + port : nodes;
    }

    private static String readString(Properties properties, String suffix, String fallback) {
        String key = PROPERTY_PREFIX + suffix;
        String value = System.getProperty(key);
        if (value == null) {
            value = System.getenv(toEnvKey(key));
        }
        if (value == null) {
            value = properties.getProperty(key);
        }
        return value == null ? fallback : value;
    }

    private static int readInt(Properties properties, String suffix, int fallback) {
        String raw = readString(properties, suffix, null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PROPERTY_PREFIX + suffix + " must be an integer: " + raw, e);
        }
    }

    private static String toEnvKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static int requirePort(int value) {
        if (value < 1 || value > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private void validateTopology() {
        if (topology == RedisTopology.SENTINEL && sentinelMasterName.isEmpty()) {
            throw new IllegalArgumentException("sentinel.master-name must be configured for Redis Sentinel topology");
        }
        if ((topology == RedisTopology.SENTINEL || topology == RedisTopology.CLUSTER) && effectiveNodes().isBlank()) {
            throw new IllegalArgumentException("nodes must be configured for Redis " + topology.wireValue() + " topology");
        }
        if (topology == RedisTopology.CLUSTER && database != 0) {
            throw new IllegalArgumentException("Redis Cluster supports database 0 only");
        }
    }

    public static final class Builder {
        private String host = "127.0.0.1";
        private int port = 6379;
        private RedisTopology topology = RedisTopology.STANDALONE;
        private String nodes = "";
        private String sentinelMasterName = "";
        private String sentinelUsername = "";
        private String sentinelPassword = "";
        private int sentinelMasterCheckMs = 1_000;
        private String username = "";
        private String password = "";
        private int database = 0;
        private int connectTimeoutMs = 500;
        private int readTimeoutMs = 500;
        private int writeTimeoutMs = 500;
        private int readConnections = 4;
        private int writeConnections = 2;
        private int maxReadInflight = 128;
        private int maxWriteInflight = 64;
        private int maxResponseBytes = 1024 * 1024;
        private int clusterMaxRedirects = 5;
        private int topologyRefreshMs = 30_000;

        private Builder() {}

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder topology(RedisTopology topology) {
            this.topology = Objects.requireNonNull(topology, "topology");
            return this;
        }

        public Builder topology(String topology) {
            this.topology = RedisTopology.parse(topology);
            return this;
        }

        public Builder nodes(String nodes) {
            this.nodes = nodes;
            return this;
        }

        public Builder sentinelMasterName(String sentinelMasterName) {
            this.sentinelMasterName = sentinelMasterName;
            return this;
        }

        public Builder sentinelUsername(String sentinelUsername) {
            this.sentinelUsername = sentinelUsername;
            return this;
        }

        public Builder sentinelPassword(String sentinelPassword) {
            this.sentinelPassword = sentinelPassword;
            return this;
        }

        public Builder sentinelMasterCheckMs(int sentinelMasterCheckMs) {
            this.sentinelMasterCheckMs = sentinelMasterCheckMs;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder database(int database) {
            this.database = database;
            return this;
        }

        public Builder connectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder readTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        public Builder writeTimeoutMs(int writeTimeoutMs) {
            this.writeTimeoutMs = writeTimeoutMs;
            return this;
        }

        public Builder readConnections(int readConnections) {
            this.readConnections = readConnections;
            return this;
        }

        public Builder writeConnections(int writeConnections) {
            this.writeConnections = writeConnections;
            return this;
        }

        public Builder maxReadInflight(int maxReadInflight) {
            this.maxReadInflight = maxReadInflight;
            return this;
        }

        public Builder maxWriteInflight(int maxWriteInflight) {
            this.maxWriteInflight = maxWriteInflight;
            return this;
        }

        public Builder maxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        public Builder clusterMaxRedirects(int clusterMaxRedirects) {
            this.clusterMaxRedirects = clusterMaxRedirects;
            return this;
        }

        public Builder topologyRefreshMs(int topologyRefreshMs) {
            this.topologyRefreshMs = topologyRefreshMs;
            return this;
        }

        public RustCacheConfig build() {
            return new RustCacheConfig(this);
        }
    }
}
