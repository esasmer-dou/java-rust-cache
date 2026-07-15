package com.reactor.rust.cache.scheduler;

import com.reactor.rust.cache.config.CacheProperties;
import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionWriterApplicationTest {

    @Test
    void closesManagedResourcesWhenSchedulerCreationFails() {
        AtomicBoolean closed = new AtomicBoolean();
        CacheWriterProjectionSettings settings = new CacheWriterProjectionSettings(
                "detail",
                "sample.detail",
                60_000,
                60_000,
                0,
                1_000,
                "sample.detail.lock",
                5_000,
                List.of());

        ProjectionWriterApplication.Builder builder = ProjectionWriterApplication.builder()
                .settings(List.of(settings))
                .manage(() -> closed.set(true));

        assertThrows(RuntimeException.class, builder::start);
        assertTrue(closed.get());
    }

    @Test
    void simpleLauncherExposesLoadedPropertiesAndOwnsResources() {
        Properties values = new Properties();
        values.setProperty("sample.writer.projections", "detail");
        values.setProperty("sample.writer.namespace", "sample");
        values.setProperty("sample.writer.cache-ttl-ms", "60000");
        values.setProperty("sample.writer.lock-name", "sample.lock");
        values.setProperty("sample.writer.lock-ttl-ms", "5000");
        values.setProperty("sample.writer.initial-delay-ms", "0");
        values.setProperty("sample.writer.interval-ms", "1000");
        values.setProperty("sample.writer.scheduler-threads", "1");
        values.setProperty("sample.writer.run-once", "false");
        AtomicBoolean closed = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> ProjectionWriterApplication.start(
                CacheProperties.from(values),
                "sample.writer",
                context -> {
                    assertFalse(context.properties().getBoolean("sample.writer.run-once"));
                    assertEquals("sample.writer", context.rootPrefix());
                    context.manage(() -> closed.set(true));
                    throw new IllegalStateException("configuration failed");
                }));

        assertTrue(closed.get());
    }
}
