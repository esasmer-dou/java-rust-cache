package com.reactor.rust.cache.projection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record CacheReaderProjectionSettings(String name, String namespace) {

    public static List<String> projectionNames(ProjectionPropertySource properties, String rootPrefix) {
        return parseProjectionNames(properties.get(rootPrefix + ".projections"), rootPrefix + ".projections");
    }

    public static List<CacheReaderProjectionSettings> resolveAll(
            ProjectionPropertySource properties,
            String rootPrefix) {
        return projectionNames(properties, rootPrefix).stream()
                .map(projection -> new CacheReaderProjectionSettings(
                        projection,
                        namespace(properties, rootPrefix, projection)))
                .toList();
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
