package com.reactor.rust.cache.projection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record CacheReaderProjectionSettings(String name, String namespace,
        com.reactor.rust.cache.versioned.CacheFreshness freshness) {
    public CacheReaderProjectionSettings(String name, String namespace) {
        this(name, namespace, com.reactor.rust.cache.versioned.CacheFreshness.DISABLED);
    }

    public static List<String> projectionNames(ProjectionPropertySource properties, String rootPrefix) {
        return parseProjectionNames(properties.get(rootPrefix + ".projections"), rootPrefix + ".projections");
    }

    public static List<CacheReaderProjectionSettings> resolveAll(
            ProjectionPropertySource properties,
            String rootPrefix) {
        return projectionNames(properties, rootPrefix).stream()
                .map(projection -> new CacheReaderProjectionSettings(
                        projection,
                        namespace(properties, rootPrefix, projection), freshness(properties, rootPrefix, projection)))
                .toList();
    }

    /** Explicit startup opt-in. Existing resolveAll never queries L1 properties or creates L1 wrappers. */
    public static List<L1ProjectionSettings> resolveAllWithL1(
            ProjectionPropertySource properties, String rootPrefix) {
        return resolveAll(properties, rootPrefix).stream()
                .map(base -> L1ProjectionSettings.resolve(properties, rootPrefix, base))
                .toList();
    }

    private static com.reactor.rust.cache.versioned.CacheFreshness freshness(ProjectionPropertySource source, String root, String projection) {
        String age = option(source, root, projection, "max-age-ms");
        String stale = option(source, root, projection, "serve-stale");
        if (stale != null && !stale.equalsIgnoreCase("true") && !stale.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("serve-stale must be true or false");
        return new com.reactor.rust.cache.versioned.CacheFreshness(age == null ? 0 : Long.parseLong(age), Boolean.parseBoolean(stale));
    }

    private static String option(ProjectionPropertySource source, String root, String projection, String suffix) {
        String specific = root + "." + projection + "." + suffix;
        String global = root + "." + suffix;
        for (String value : new String[] {source.getRuntimeOverride(specific), source.getRuntimeOverride(global),
                source.getFileOptional(specific), source.getFileOptional(global)}) {
            if (hasText(value)) return value.trim();
        }
        return null;
    }

    private static String namespace(ProjectionPropertySource properties, String rootPrefix, String projection) {
        String specificKey = rootPrefix + "." + projection + ".namespace";
        String specificRuntime = properties.getRuntimeOverride(specificKey);
        if (hasText(specificRuntime)) {
            return specificRuntime;
        }
        String baseRuntime = properties.getRuntimeOverride(rootPrefix + ".namespace");
        if (hasText(baseRuntime)) {
            return baseRuntime + "." + projection;
        }
        String specificFile = properties.getFileOptional(specificKey);
        if (hasText(specificFile)) {
            return specificFile;
        }
        return properties.get(rootPrefix + ".namespace") + "." + projection;
    }

    static List<String> parseProjectionNames(String value, String key) {
        Set<String> names = new LinkedHashSet<>();
        for (String item : value.split(",")) {
            String name = item.trim();
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException(key + " must contain at least one projection");
        }
        return List.copyOf(names);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
