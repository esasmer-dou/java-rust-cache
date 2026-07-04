package com.reactor.rust.cache.scheduler;

import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProjectionRefreshScheduler implements AutoCloseable {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final List<ProjectionSchedule> schedules;
    private final ProjectionRefresher refresher;
    private final ScheduledExecutorService executor;
    private final boolean runOnce;
    private final CountDownLatch firstRunDone;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ProjectionRefreshScheduler(Builder builder) {
        this.schedules = builder.settings.stream().map(ProjectionSchedule::from).toList();
        this.refresher = Objects.requireNonNull(builder.refresher, "refresher");
        this.runOnce = builder.runOnce;
        this.firstRunDone = new CountDownLatch(schedules.size());
        int threads = boundedThreads(builder.schedulerThreads, schedules.size());
        String prefix = builder.threadNamePrefix;
        this.executor = Executors.newScheduledThreadPool(threads, task -> {
            Thread thread = new Thread(task, prefix + "-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public void start() {
        if (closed.get()) {
            return;
        }
        logConfigurationWarnings("startup");
        if (runOnce) {
            for (ProjectionSchedule schedule : schedules) {
                executor.schedule(() -> safeRefresh(schedule), schedule.initialDelayMillis(), TimeUnit.MILLISECONDS);
            }
            return;
        }
        for (ProjectionSchedule schedule : schedules) {
            executor.scheduleWithFixedDelay(
                    () -> safeRefresh(schedule),
                    schedule.initialDelayMillis(),
                    schedule.intervalMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    public void awaitFirstRun() {
        try {
            firstRunDone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private void safeRefresh(ProjectionSchedule schedule) {
        try {
            logConfigurationWarnings(schedule, "refresh");
            ProjectionRefreshResult result = refresher.refresh(
                    schedule.projection(),
                    schedule.lockName(),
                    schedule.lockTtlMillis());
            if (result.skippedLocked()) {
                System.out.println("cache refresh skipped projection=" + result.projection()
                        + " lock=" + schedule.lockName()
                        + " reason=another replica owns the Redis lock");
            } else {
                System.out.println("cache refresh published projection=" + result.projection()
                        + " version=" + result.version()
                        + " keys=" + result.writtenKeys());
            }
        } catch (Throwable error) {
            System.err.println("cache refresh failed projection=" + schedule.projection()
                    + ": " + error.getMessage());
            error.printStackTrace(System.err);
        } finally {
            firstRunDone.countDown();
        }
    }

    private void logConfigurationWarnings(String phase) {
        for (ProjectionSchedule schedule : schedules) {
            logConfigurationWarnings(schedule, phase);
        }
    }

    private static void logConfigurationWarnings(ProjectionSchedule schedule, String phase) {
        for (String warning : schedule.warnings()) {
            System.err.println("WARNING cache writer config " + warning + " phase=" + phase);
        }
    }

    private static int boundedThreads(int configured, int projectionCount) {
        if (configured <= 0) {
            throw new IllegalArgumentException("schedulerThreads must be positive");
        }
        return Math.max(1, Math.min(configured, Math.max(1, projectionCount)));
    }

    private record ProjectionSchedule(
            String projection,
            long initialDelayMillis,
            long intervalMillis,
            String lockName,
            long lockTtlMillis,
            List<String> warnings) {

        static ProjectionSchedule from(CacheWriterProjectionSettings settings) {
            return new ProjectionSchedule(
                    settings.name(),
                    settings.initialDelayMillis(),
                    settings.intervalMillis(),
                    settings.lockName(),
                    settings.lockTtlMillis(),
                    settings.warnings());
        }
    }

    @FunctionalInterface
    public interface ProjectionRefresher {
        ProjectionRefreshResult refresh(String projection, String lockName, long lockTtlMillis) throws Exception;
    }

    public static final class Builder {
        private List<CacheWriterProjectionSettings> settings = List.of();
        private ProjectionRefresher refresher;
        private boolean runOnce;
        private int schedulerThreads = 1;
        private String threadNamePrefix = "cache-writer";

        private Builder() {}

        public Builder settings(List<CacheWriterProjectionSettings> settings) {
            this.settings = List.copyOf(Objects.requireNonNull(settings, "settings"));
            if (this.settings.isEmpty()) {
                throw new IllegalArgumentException("settings must not be empty");
            }
            return this;
        }

        public Builder refresher(ProjectionRefresher refresher) {
            this.refresher = Objects.requireNonNull(refresher, "refresher");
            return this;
        }

        public Builder runOnce(boolean runOnce) {
            this.runOnce = runOnce;
            return this;
        }

        public Builder schedulerThreads(int schedulerThreads) {
            this.schedulerThreads = schedulerThreads;
            return this;
        }

        public Builder threadNamePrefix(String threadNamePrefix) {
            if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
                throw new IllegalArgumentException("threadNamePrefix must not be blank");
            }
            this.threadNamePrefix = threadNamePrefix.trim();
            return this;
        }

        public ProjectionRefreshScheduler build() {
            List<String> missing = new ArrayList<>();
            if (settings.isEmpty()) {
                missing.add("settings");
            }
            if (refresher == null) {
                missing.add("refresher");
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Missing scheduler builder fields: " + missing);
            }
            return new ProjectionRefreshScheduler(this);
        }
    }
}
