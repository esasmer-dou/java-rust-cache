package com.reactor.rust.cache.scheduler;

import com.reactor.rust.cache.core.RedisCacheException;
import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionRefreshSchedulerTest {

    @Test
    void anchoredDeadlinePreservesPhaseAndSkipsMissedSlots() {
        assertEquals(1_500, ProjectionRefreshScheduler.nextPhase(1_000, 1_040, 500));
        assertEquals(3_000, ProjectionRefreshScheduler.nextPhase(1_000, 2_850, 500));
        assertEquals(2_000, ProjectionRefreshScheduler.nextPhase(1_000, 1_500, 500));
        assertEquals(Long.MIN_VALUE + 499,
                ProjectionRefreshScheduler.nextPhase(Long.MAX_VALUE, Long.MIN_VALUE + 20, 500));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionRefreshScheduler.nextPhase(0, 0, 0));
    }

    @Test
    void anchoredProjectionDoesNotOverlapAndStopsAfterClose() throws Exception {
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var scheduler = ProjectionRefreshScheduler.builder()
                .settings(List.of(settings("detail", 0, 10)))
                .phaseAnchored(true).schedulerThreads(2)
                .refresher((name, lock, ttl) -> {
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try { calls.incrementAndGet(); Thread.sleep(35); }
                    finally { active.decrementAndGet(); }
                    return ProjectionRefreshResult.published(name, "v", 1);
                }).observer(ProjectionRefreshObserver.noop()).build()) {
            scheduler.start();
            scheduler.awaitFirstRun(5, TimeUnit.SECONDS);
            Thread.sleep(150);
            assertEquals(1, peak.get());
            assertTrue(calls.get() < 12, "No immediate catch-up burst");
        }
        int stopped = calls.get();
        Thread.sleep(75);
        assertEquals(stopped, calls.get());
    }

    @Test
    void propagatesFirstRefreshFailureToStartupGate() {
        try (ProjectionRefreshScheduler scheduler = ProjectionRefreshScheduler.builder()
                .settings(List.of(settings("detail", 0, 1_000)))
                .refresher((projection, lockName, lockTtl) -> {
                    throw new IllegalStateException("database unavailable");
                })
                .runOnce(true)
                .firstRunTimeoutMillis(1_000)
                .observer(ProjectionRefreshObserver.noop())
                .build()) {
            scheduler.start();

            RedisCacheException error = assertThrows(RedisCacheException.class, scheduler::awaitFirstRun);
            assertTrue(error.getMessage().contains("failed"));
            assertFalse(scheduler.isReady());
            assertEquals(1, scheduler.statuses().get(0).failureCount());
        }
    }

    @Test
    void tracksFirstRunPerProjectionInsteadOfCountingRepeatedFastProjectionRuns() {
        try (ProjectionRefreshScheduler scheduler = ProjectionRefreshScheduler.builder()
                .settings(List.of(
                        settings("fast", 0, 1),
                        settings("delayed", 250, 1_000)
                ))
                .refresher((projection, lockName, lockTtl) ->
                        ProjectionRefreshResult.published(projection, "v1", 1))
                .firstRunTimeoutMillis(1_000)
                .observer(ProjectionRefreshObserver.noop())
                .build()) {
            scheduler.start();

            assertThrows(
                    RedisCacheException.class,
                    () -> scheduler.awaitFirstRun(50, TimeUnit.MILLISECONDS)
            );
            scheduler.awaitFirstRun();
            assertTrue(scheduler.isReady());
            assertEquals(2, scheduler.statuses().size());
        }
    }

    @Test
    void rejectsDuplicateProjectionNames() {
        assertThrows(IllegalArgumentException.class, () -> ProjectionRefreshScheduler.builder()
                .settings(List.of(
                        settings("detail", 0, 1_000),
                        settings("detail", 0, 2_000)
                ))
                .refresher((projection, lockName, lockTtl) ->
                        ProjectionRefreshResult.published(projection, "v1", 1))
                .observer(ProjectionRefreshObserver.noop())
                .build());
    }

    @Test
    void observerFailureDoesNotTurnSuccessfulRefreshIntoBusinessFailure() {
        ProjectionRefreshObserver brokenObserver = new ProjectionRefreshObserver() {
            @Override
            public void onPublished(ProjectionRefreshResult result) {
                throw new IllegalStateException("metrics backend unavailable");
            }

            @Override
            public void onSkipped(ProjectionRefreshResult result, String lockName) {
            }

            @Override
            public void onFailure(String projection, Throwable error) {
            }

            @Override
            public void onConfigurationWarning(String warning, String phase) {
            }
        };
        try (ProjectionRefreshScheduler scheduler = ProjectionRefreshScheduler.builder()
                .settings(List.of(settings("detail", 0, 1_000)))
                .refresher((projection, lockName, lockTtl) ->
                        ProjectionRefreshResult.published(projection, "v1", 1))
                .runOnce(true)
                .firstRunTimeoutMillis(1_000)
                .observer(brokenObserver)
                .build()) {
            scheduler.start();

            scheduler.awaitFirstRun();
            assertTrue(scheduler.isReady());
            assertEquals(0, scheduler.statuses().get(0).failureCount());
        }
    }

    @Test
    void rejectsNegativeSchedulerThreadStackSize() {
        assertThrows(IllegalArgumentException.class, () -> ProjectionRefreshScheduler.builder()
                .schedulerThreadStackBytes(-1));
    }

    private static CacheWriterProjectionSettings settings(String name, long initialDelay, long interval) {
        return new CacheWriterProjectionSettings(
                name,
                "cache." + name,
                60_000,
                60_000,
                initialDelay,
                interval,
                "refresh." + name,
                30_000,
                List.of()
        );
    }
}
