package com.reactor.rust.cache.projection;

import com.reactor.rust.cache.config.CacheProperties;
import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class CacheFreshnessSettingsTest {
    @Test void blankOptionalValuesPreserveDefaultsAndRuntimeOverrideWins() {
        var values=new Properties();
        values.setProperty("test.cache.projections","detail");
        values.setProperty("test.cache.namespace","customer");
        var source=CacheProperties.from(values);
        assertEquals(0,CacheReaderProjectionSettings.resolveAll(source,"test.cache").getFirst().freshness().maxAgeMillis());
        values.setProperty("test.cache.detail.max-age-ms","1000");
        values.setProperty("test.cache.detail.serve-stale","false");
        try {
            System.setProperty("test.cache.max-age-ms","2000");
            System.setProperty("test.cache.serve-stale","true");
            var setting=CacheReaderProjectionSettings.resolveAll(CacheProperties.from(values),"test.cache").getFirst();
            assertEquals(2000,setting.freshness().maxAgeMillis());
            assertTrue(setting.freshness().serveStale());
            System.setProperty("test.cache.detail.max-age-ms","3000");
            assertEquals(3000,CacheReaderProjectionSettings.resolveAll(CacheProperties.from(values),"test.cache").getFirst().freshness().maxAgeMillis());
        } finally {
            System.clearProperty("test.cache.max-age-ms");System.clearProperty("test.cache.serve-stale");System.clearProperty("test.cache.detail.max-age-ms");
        }
    }
}
