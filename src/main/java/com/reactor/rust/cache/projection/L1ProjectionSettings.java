package com.reactor.rust.cache.projection;

import com.reactor.rust.cache.versioned.VersionedL1Cache;
import java.util.Objects;

/** Explicit L1 startup settings. Null options means absent/zero max-entries, not an enabled reader. */
public record L1ProjectionSettings(CacheReaderProjectionSettings base, VersionedL1Cache.Options options) {
    public L1ProjectionSettings {
        Objects.requireNonNull(base, "base");
    }

    static L1ProjectionSettings resolve(ProjectionPropertySource source, String root, CacheReaderProjectionSettings base) {
        String entries = option(source, root, base.name(), "max-entries");
        int count = entries == null ? 0 : Integer.parseInt(entries);
        if (count == 0) return new L1ProjectionSettings(base, null);
        return new L1ProjectionSettings(base, new VersionedL1Cache.Options(count,
                (int) number(source, root, base.name(), "max-entry-bytes", 8192, Integer.MAX_VALUE),
                (int) number(source, root, base.name(), "max-key-chars", 256, Integer.MAX_VALUE),
                number(source, root, base.name(), "max-age-ms", 1000, Long.MAX_VALUE),
                number(source, root, base.name(), "max-bytes", 1024 * 1024, Long.MAX_VALUE)));
    }

    private static long number(ProjectionPropertySource source, String root, String projection,
                               String name, long fallback, long maximum) {
        String raw = option(source, root, projection, name);
        long value = raw == null ? fallback : Long.parseLong(raw);
        if (value < 0 || value > maximum) throw new IllegalArgumentException("L1 option overflow: " + name);
        return value;
    }

    private static String option(ProjectionPropertySource source, String root, String projection, String name) {
        String specific = root + "." + projection + ".l1." + name;
        String global = root + ".l1." + name;
        for (String value : new String[]{source.getRuntimeOverride(specific), source.getRuntimeOverride(global),
                source.getFileOptional(specific), source.getFileOptional(global)}) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
