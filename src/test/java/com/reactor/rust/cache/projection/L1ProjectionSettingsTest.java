package com.reactor.rust.cache.projection;

import com.reactor.rust.cache.config.CacheProperties;
import com.reactor.rust.cache.versioned.CacheFreshness;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class L1ProjectionSettingsTest {
    @Test
    void absentOrZeroEntriesLeaveOldConstructorsAndFactoryExplicitlyDisabled() {
        assertSame(CacheFreshness.DISABLED, new CacheReaderProjectionSettings("p", "ns").freshness());
        assertSame(CacheFreshness.DISABLED, new CacheReaderProjectionSettings("p", "ns", CacheFreshness.DISABLED).freshness());
        Properties values = properties();
        assertNull(resolve(values).options());
        values.setProperty("l1test.l1.max-entries", "0");
        assertNull(resolve(values).options());
        assertThrows(IllegalArgumentException.class, () -> VersionedJsonProjectionReaders.createL1Reader(null, resolve(values), 1000));
        assertThrows(NullPointerException.class, () -> new L1ProjectionSettings(null, null));
    }

    @Test
    void optInOptionsHaveConservativeOneMiBDefaultsAndCanSetAnExactBudget() {
        Properties values = properties(); values.setProperty("l1test.l1.max-entries", "64");
        var setting = resolve(values);
        assertEquals(CacheReaderProjectionSettings.resolveAll(CacheProperties.from(values), "l1test").getFirst(), setting.base());
        assertEquals(64, setting.options().maxEntries()); assertEquals(8192, setting.options().maxEntryBytes());
        assertEquals(1000, setting.options().maxAgeMillis()); assertEquals(1024 * 1024, setting.options().maxBytes());
        assertTrue(setting.options().totalBytesEstimate() <= setting.options().maxBytes());
        values.setProperty("l1test.l1.max-bytes", Long.toString(setting.options().totalBytesEstimate()));
        assertEquals(setting.options().totalBytesEstimate(), resolve(values).options().maxBytes());
    }

    @Test
    void runtimeGlobalOverridesFileSpecificAndRuntimeSpecificOverridesGlobal() {
        Properties values = properties(); values.setProperty("l1test.p.l1.max-entries", "2");
        values.setProperty("l1test.p.l1.max-age-ms", "1000");
        try {
            System.setProperty("l1test.l1.max-entries", "4"); System.setProperty("l1test.l1.max-age-ms", "2000");
            assertEquals(4, resolve(values).options().maxEntries()); assertEquals(2000, resolve(values).options().maxAgeMillis());
            System.setProperty("l1test.p.l1.max-entries", "8"); assertEquals(8, resolve(values).options().maxEntries());
        } finally {
            System.clearProperty("l1test.l1.max-entries"); System.clearProperty("l1test.l1.max-age-ms");
            System.clearProperty("l1test.p.l1.max-entries");
        }
    }

    @Test
    void invalidBudgetCartesianProductNegativeAndTruncatingIntegerValuesFailAtStartup() {
        for (String[] option : new String[][]{{"max-entries", "3"}, {"max-entry-bytes", "262145"},
                {"max-entry-bytes", "-4294967295"}, {"max-entry-bytes", "4294967297"},
                {"max-bytes", "1"}, {"max-bytes", "9223372036854775807"}, {"max-key-chars", "0"},
                {"max-age-ms", "-1"}}) {
            Properties values = properties(); values.setProperty("l1test.l1.max-entries", "2");
            values.setProperty("l1test.l1." + option[0], option[1]);
            assertThrows(IllegalArgumentException.class, () -> resolve(values), option[0] + '=' + option[1]);
        }
        Properties values = properties(); values.setProperty("l1test.l1.max-entries", "65536");
        values.setProperty("l1test.l1.max-entry-bytes", "262144");
        assertThrows(IllegalArgumentException.class, () -> resolve(values));
    }

    @Test
    void oldThreeComponentRecordPatternCompilesAndRunsOnStandardJava21() {
        var setting = new CacheReaderProjectionSettings("p", "ns", new CacheFreshness(42, true));
        String unpacked = switch (setting) {
            case CacheReaderProjectionSettings(String name, String namespace, CacheFreshness freshness) ->
                    name + '|' + namespace + '|' + freshness.maxAgeMillis() + '|' + freshness.serveStale();
        };
        assertEquals("p|ns|42|true", unpacked);
    }

    @Test
    void legacyRecordHasExactlyItsOriginalThreeComponentsAndTypes() {
        var components = CacheReaderProjectionSettings.class.getRecordComponents();
        assertEquals(3, components.length);
        assertArrayEquals(new String[]{"name", "namespace", "freshness"},
                Arrays.stream(components).map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new));
        assertArrayEquals(new Class<?>[]{String.class, String.class, CacheFreshness.class},
                Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
    }

    @Test
    void legacyResolveAllMakesOnlyOriginalLookupsAndNeverAsksL1EvenWhenMalformed() {
        Properties values = properties();
        values.setProperty("l1test.l1.max-entries", "not-an-integer");
        values.setProperty("l1test.p.l1.max-bytes", "invalid-budget");
        var source = new RecordingSource(values);
        assertEquals(List.of(new CacheReaderProjectionSettings("p", "ns.p", CacheFreshness.DISABLED)),
                CacheReaderProjectionSettings.resolveAll(source, "l1test"));
        assertEquals(List.of("get:l1test.projections", "runtime:l1test.p.namespace", "runtime:l1test.namespace",
                "file:l1test.p.namespace", "get:l1test.namespace", "runtime:l1test.p.max-age-ms",
                "runtime:l1test.max-age-ms", "file:l1test.p.max-age-ms", "file:l1test.max-age-ms",
                "runtime:l1test.p.serve-stale", "runtime:l1test.serve-stale", "file:l1test.p.serve-stale",
                "file:l1test.serve-stale"), source.lookups);
        assertThrows(IllegalArgumentException.class,
                () -> CacheReaderProjectionSettings.resolveAllWithL1(CacheProperties.from(values), "l1test"));
    }

    private static Properties properties() {
        Properties values = new Properties(); values.setProperty("l1test.projections", "p");
        values.setProperty("l1test.namespace", "ns"); return values;
    }
    private static L1ProjectionSettings resolve(Properties properties) {
        return CacheReaderProjectionSettings.resolveAllWithL1(CacheProperties.from(properties), "l1test").getFirst();
    }

    private static final class RecordingSource implements ProjectionPropertySource {
        private final Properties values;
        private final List<String> lookups = new ArrayList<>();
        private RecordingSource(Properties values) { this.values = values; }
        private String lookup(String operation, String key) {
            lookups.add(operation + ':' + key);
            if (key.contains(".l1.")) throw new AssertionError("Legacy resolver queried " + key);
            return operation.equals("runtime") ? null : values.getProperty(key);
        }
        @Override public String get(String key) { return lookup("get", key); }
        @Override public String getOptional(String key) { return lookup("optional", key); }
        @Override public String getRuntimeOverride(String key) { return lookup("runtime", key); }
        @Override public String getFileOptional(String key) { return lookup("file", key); }
    }
}
