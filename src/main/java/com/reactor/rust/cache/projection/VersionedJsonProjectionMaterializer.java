package com.reactor.rust.cache.projection;

import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.scheduler.ProjectionRefreshResult;
import com.reactor.rust.cache.versioned.VersionedJsonCacheWriter;
import com.reactor.rust.cache.versioned.VersionedJsonCacheWriter.SnapshotResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Declarative projection-to-writer registry with fenced Redis lock execution.
 */
public final class VersionedJsonProjectionMaterializer {

    private final RustCache cache;
    private final Map<String, ProjectionTarget> targets;
    private final Map<String, ProjectionWriter> writers;

    private VersionedJsonProjectionMaterializer(Builder builder) {
        this.cache = builder.cache;
        this.writers = Map.copyOf(builder.writers);
        Map<String, ProjectionTarget> targets = new LinkedHashMap<>();
        for (CacheWriterProjectionSettings setting : builder.settings) {
            if (!writers.containsKey(setting.name())) {
                throw new IllegalArgumentException("No writer declared for configured projection: " + setting.name());
            }
            targets.put(setting.name(), new ProjectionTarget(
                    setting.name(),
                    setting.namespace(),
                    setting.effectiveCacheTtlMillis(),
                    cache.versionedJsonWriter(setting.namespace(), builder.batchSize)));
        }
        this.targets = Map.copyOf(targets);
    }

    public static Builder builder(
            RustCache cache,
            List<CacheWriterProjectionSettings> settings,
            int batchSize) {
        return new Builder(cache, settings, batchSize);
    }

    public ProjectionRefreshResult refresh(String projection, String lockName, long lockTtlMillis) {
        ProjectionTarget target = targets.get(projection);
        if (target == null) {
            throw new IllegalArgumentException("Projection is not configured: " + projection);
        }
        ProjectionWriter writer = writers.get(projection);
        SnapshotResult[] result = new SnapshotResult[1];
        boolean published = cache.locks().runOnceChecked(lockName, lockTtlMillis, lock -> {
            result[0] = writer.write(target);
            lock.ensureValid();
        });
        if (!published) {
            return ProjectionRefreshResult.skipped(projection);
        }
        SnapshotResult snapshot = result[0];
        if (snapshot == null) {
            throw new IllegalStateException("Projection writer returned no snapshot result: " + projection);
        }
        return ProjectionRefreshResult.published(projection, snapshot.version(), snapshot.writtenKeys());
    }

    public Set<String> projectionNames() {
        return targets.keySet();
    }

    @FunctionalInterface
    public interface ProjectionWriter {
        SnapshotResult write(ProjectionTarget target) throws Exception;
    }

    public record ProjectionTarget(
            String name,
            String namespace,
            long ttlMillis,
            VersionedJsonCacheWriter writer) {}

    public static final class Builder {

        private final RustCache cache;
        private final List<CacheWriterProjectionSettings> settings;
        private final int batchSize;
        private final Map<String, ProjectionWriter> writers = new LinkedHashMap<>();

        private Builder(RustCache cache, List<CacheWriterProjectionSettings> settings, int batchSize) {
            this.cache = Objects.requireNonNull(cache, "cache");
            this.settings = List.copyOf(Objects.requireNonNull(settings, "settings"));
            if (this.settings.isEmpty()) {
                throw new IllegalArgumentException("settings must not be empty");
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be positive");
            }
            this.batchSize = batchSize;
        }

        public Builder projection(String name, ProjectionWriter writer) {
            String normalized = requireText(name, "name");
            if (writers.putIfAbsent(normalized, Objects.requireNonNull(writer, "writer")) != null) {
                throw new IllegalArgumentException("Projection writer already declared: " + normalized);
            }
            return this;
        }

        public Builder projection(ProjectionId projection, ProjectionWriter writer) {
            Objects.requireNonNull(projection, "projection");
            return projection(projection.value(), writer);
        }

        /**
         * Accepts a shared domain enum without making that model artifact depend on this library.
         */
        public Builder projection(Supplier<String> projection, ProjectionWriter writer) {
            return projection(requireValue(projection, "projection"), writer);
        }

        public VersionedJsonProjectionMaterializer build() {
            return new VersionedJsonProjectionMaterializer(this);
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value.trim();
        }

        private static String requireValue(Supplier<String> value, String name) {
            Objects.requireNonNull(value, name);
            return requireText(value.get(), name);
        }
    }
}
