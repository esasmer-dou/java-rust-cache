package com.reactor.rust.cache.scheduler;

import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
}
