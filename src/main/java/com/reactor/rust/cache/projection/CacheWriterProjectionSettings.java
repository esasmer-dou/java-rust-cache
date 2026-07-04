package com.reactor.rust.cache.projection;

import java.util.ArrayList;
import java.util.List;

public record CacheWriterProjectionSettings(
        String name,
        String namespace,
        long configuredCacheTtlMillis,
        long effectiveCacheTtlMillis,
        long initialDelayMillis,
        long intervalMillis,
        String lockName,
        long lockTtlMillis,
        List<String> warnings) {

    public CacheWriterProjectionSettings {
        warnings = List.copyOf(warnings);
    }

    public static List<String> projectionNames(ProjectionPropertySource properties, String rootPrefix) {
        return CacheReaderProjectionSettings.parseProjectionNames(
                properties.get(rootPrefix + ".projections"),
                rootPrefix + ".projections");
    }

    public static List<CacheWriterProjectionSettings> resolveAll(
            ProjectionPropertySource properties,
            String rootPrefix) {
        List<String> globalWarnings = new ArrayList<>();
        long ttlSafetyMarginMillis = baseLong(
                properties,
                rootPrefix + ".cache-ttl-safety-margin-ms",
                "30000",
                true,
                globalWarnings);
        return projectionNames(properties, rootPrefix).stream()
                .map(projection -> resolve(properties, rootPrefix, projection, ttlSafetyMarginMillis, globalWarnings))
                .toList();
    }

    private static CacheWriterProjectionSettings resolve(
            ProjectionPropertySource properties,
            String rootPrefix,
            String projection,
            long ttlSafetyMarginMillis,
            List<String> globalWarnings) {
        List<String> warnings = new ArrayList<>(globalWarnings);
        String namespace = projectionText(properties, rootPrefix, projection, "namespace", rootPrefix + ".namespace", true);
        long configuredCacheTtlMillis = projectionLong(
                properties,
                rootPrefix,
                projection,
                "cache-ttl-ms",
                rootPrefix + ".cache-ttl-ms",
                true,
                warnings);
        long initialDelayMillis = projectionLong(
                properties,
                rootPrefix,
                projection,
                "initial-delay-ms",
                rootPrefix + ".initial-delay-ms",
                false,
                warnings);
        long intervalMillis = projectionLong(
                properties,
                rootPrefix,
                projection,
                "interval-ms",
                rootPrefix + ".interval-ms",
                true,
                warnings);
        long lockTtlMillis = projectionLong(
                properties,
                rootPrefix,
                projection,
                "lock-ttl-ms",
                rootPrefix + ".lock-ttl-ms",
                true,
                warnings);
        long effectiveCacheTtlMillis = effectiveCacheTtl(
                rootPrefix,
                projection,
                configuredCacheTtlMillis,
                intervalMillis,
                ttlSafetyMarginMillis,
                warnings);
        return new CacheWriterProjectionSettings(
                projection,
                namespace,
                configuredCacheTtlMillis,
                effectiveCacheTtlMillis,
                initialDelayMillis,
                intervalMillis,
                projectionText(properties, rootPrefix, projection, "lock-name", rootPrefix + ".lock-name", true),
                lockTtlMillis,
                warnings);
    }

    private static long effectiveCacheTtl(
            String rootPrefix,
            String projection,
            long configuredCacheTtlMillis,
            long intervalMillis,
            long ttlSafetyMarginMillis,
            List<String> warnings) {
        if (configuredCacheTtlMillis > intervalMillis) {
            return configuredCacheTtlMillis;
        }
        long effective = safeAdd(intervalMillis, ttlSafetyMarginMillis);
        warnings.add("projection=" + projection
                + " property=" + rootPrefix + "." + projection + ".cache-ttl-ms"
                + " configuredCacheTtlMs=" + configuredCacheTtlMillis
                + " intervalMs=" + intervalMillis
                + " effectiveCacheTtlMs=" + effective
                + " safetyMarginMs=" + ttlSafetyMarginMillis
                + " reason=cache-ttl-ms-must-be-greater-than-interval");
        return effective;
    }

    private static String projectionText(
            ProjectionPropertySource properties,
            String rootPrefix,
            String projection,
            String suffix,
            String baseKey,
            boolean appendProjectionForBase) {
        String specificKey = rootPrefix + "." + projection + "." + suffix;
        String specificRuntime = properties.getRuntimeOverride(specificKey);
        if (hasText(specificRuntime)) {
            return specificRuntime;
        }
        String baseRuntime = properties.getRuntimeOverride(baseKey);
        if (hasText(baseRuntime)) {
            return appendProjectionForBase ? baseRuntime + "." + projection : baseRuntime;
        }
        String specificFile = properties.getFileOptional(specificKey);
        if (hasText(specificFile)) {
            return specificFile;
        }
        String baseFile = properties.get(baseKey);
        return appendProjectionForBase ? baseFile + "." + projection : baseFile;
    }

    private static long projectionLong(
            ProjectionPropertySource properties,
            String rootPrefix,
            String projection,
            String suffix,
            String baseKey,
            boolean positive,
            List<String> warnings) {
        String specificKey = rootPrefix + "." + projection + "." + suffix;
        ValueCandidate[] candidates = {
                new ValueCandidate(specificKey, "runtime", properties.getRuntimeOverride(specificKey)),
                new ValueCandidate(baseKey, "runtime", properties.getRuntimeOverride(baseKey)),
                new ValueCandidate(specificKey, "file", properties.getFileOptional(specificKey)),
                new ValueCandidate(baseKey, "file", properties.getFileOptional(baseKey))
        };
        for (ValueCandidate candidate : candidates) {
            if (!hasText(candidate.value())) {
                continue;
            }
            Long parsed = tryParseMillis(candidate, positive, warnings);
            if (parsed != null) {
                return parsed;
            }
        }
        return baseLong(properties, baseKey, "1", positive, warnings);
    }

    private static long baseLong(
            ProjectionPropertySource properties,
            String key,
            String hardDefault,
            boolean positive,
            List<String> warnings) {
        ValueCandidate[] candidates = {
                new ValueCandidate(key, "runtime", properties.getRuntimeOverride(key)),
                new ValueCandidate(key, "file", properties.getFileOptional(key)),
                new ValueCandidate(key, "hard-default", hardDefault)
        };
        for (ValueCandidate candidate : candidates) {
            if (!hasText(candidate.value())) {
                continue;
            }
            Long parsed = tryParseMillis(candidate, positive, warnings);
            if (parsed != null) {
                return parsed;
            }
        }
        return 1L;
    }

    private static Long tryParseMillis(ValueCandidate candidate, boolean positive, List<String> warnings) {
        try {
            long parsed = Long.parseLong(candidate.value());
            if (positive && parsed <= 0) {
                warnings.add(invalidWarning(candidate, "must-be-positive"));
                return null;
            }
            if (!positive && parsed < 0) {
                warnings.add(invalidWarning(candidate, "must-not-be-negative"));
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            warnings.add(invalidWarning(candidate, "must-be-long"));
            return null;
        }
    }

    private static String invalidWarning(ValueCandidate candidate, String reason) {
        return "property=" + candidate.key()
                + " source=" + candidate.source()
                + " invalidValue=" + candidate.value()
                + " reason=" + reason
                + " action=ignored-and-fallback-used";
    }

    private static long safeAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ValueCandidate(String key, String source, String value) {}
}
