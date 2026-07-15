package com.reactor.rust.cache.scheduler;

import com.reactor.rust.cache.core.RedisCacheException;
import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ProjectionRefreshScheduler implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ProjectionRefreshScheduler.class.getName());
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final List<ProjectionSchedule> schedules;
    private final ProjectionRefresher refresher;
    private final ProjectionRefreshObserver observer;
    private final ScheduledExecutorService executor;
    private final boolean runOnce;
    private final CountDownLatch firstRunDone;
    private final long firstRunTimeoutMillis;
    private final ConcurrentHashMap<String, ProjectionRuntimeState> runtimeStates = new ConcurrentHashMap<>();
    private final java.util.Set<String> firstRunRecorded = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Throwable> firstRunFailure = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private ProjectionRefreshScheduler(Builder builder) {
        this.schedules = builder.settings.stream().map(ProjectionSchedule::from).toList();
        this.refresher = Objects.requireNonNull(builder.refresher, "refresher");
        this.observer = Objects.requireNonNull(builder.observer, "observer");
        this.runOnce = builder.runOnce;
        this.firstRunDone = new CountDownLatch(schedules.size());
        this.firstRunTimeoutMillis = builder.firstRunTimeoutMillis;
        for (ProjectionSchedule schedule : schedules) {
            runtimeStates.put(schedule.projection(), new ProjectionRuntimeState(schedule.projection()));
        }
        int threads = boundedThreads(builder.schedulerThreads, schedules.size());
        String prefix = builder.threadNamePrefix;
        this.executor = Executors.newScheduledThreadPool(threads, task -> {
            Thread thread = new Thread(
                    null,
                    task,
                    prefix + "-" + THREAD_SEQUENCE.incrementAndGet(),
                    builder.schedulerThreadStackBytes,
                    false);
            thread.setDaemon(false);
            return thread;
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Projection refresh scheduler is closed");
        }
        if (!started.compareAndSet(false, true)) {
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
        awaitFirstRun(firstRunTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void awaitFirstRun(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (!started.get()) {
            throw new IllegalStateException("Projection refresh scheduler has not been started");
        }
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            if (!firstRunDone.await(timeout, unit)) {
                throw new RedisCacheException(
                        "Timed out waiting for the first cache refresh of "
                                + firstRunDone.getCount() + " projection(s)"
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedisCacheException("Interrupted while waiting for the first cache refresh", e);
        }
        Throwable failure = firstRunFailure.get();
        if (failure != null) {
            throw new RedisCacheException("At least one projection failed during its first cache refresh", failure);
        }
    }

    public boolean isReady() {
        return started.get() && firstRunDone.getCount() == 0 && firstRunFailure.get() == null;
    }

    public List<ProjectionStatus> statuses() {
        return schedules.stream()
                .map(schedule -> runtimeStates.get(schedule.projection()).snapshot())
                .toList();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private void safeRefresh(ProjectionSchedule schedule) {
        ProjectionRuntimeState state = runtimeStates.get(schedule.projection());
        state.start();
        Throwable failure = null;
        try {
            logConfigurationWarnings(schedule, "refresh");
            ProjectionRefreshResult result = refresher.refresh(
                    schedule.projection(),
                    schedule.lockName(),
                    schedule.lockTtlMillis());
            if (result.skippedLocked()) {
                state.skipped();
                notifyObserver(
                        () -> observer.onSkipped(result, schedule.lockName()),
                        "skipped",
                        schedule.projection());
            } else {
                state.succeeded(result);
                notifyObserver(() -> observer.onPublished(result), "published", schedule.projection());
            }
        } catch (Exception error) {
            failure = error;
            state.failed(error);
            notifyObserver(() -> observer.onFailure(schedule.projection(), error), "failure", schedule.projection());
        } catch (Error error) {
            // Record fatal JVM failures for readiness/diagnostics, but never turn them into a
            // recoverable scheduler outcome. The executor must observe the Error.
            failure = error;
            state.failed(error);
            notifyObserver(() -> observer.onFailure(schedule.projection(), error), "fatal failure", schedule.projection());
            throw error;
        } finally {
            state.finish();
            if (firstRunRecorded.add(schedule.projection())) {
                if (failure != null) {
                    firstRunFailure.compareAndSet(null, failure);
                }
                firstRunDone.countDown();
            }
        }
    }

    private void logConfigurationWarnings(String phase) {
        for (ProjectionSchedule schedule : schedules) {
            logConfigurationWarnings(schedule, phase);
        }
    }

    private void logConfigurationWarnings(ProjectionSchedule schedule, String phase) {
        for (String warning : schedule.warnings()) {
            notifyObserver(
                    () -> observer.onConfigurationWarning(warning, phase),
                    "configuration warning",
                    schedule.projection());
        }
    }

    private static void notifyObserver(Runnable callback, String event, String projection) {
        try {
            callback.run();
        } catch (RuntimeException observerFailure) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Cache projection observer failed for {0} during {1}: {2}",
                    projection,
                    event,
                    observerFailure.toString());
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

    public record ProjectionStatus(
            String projection,
            boolean running,
            long lastStartedEpochMillis,
            long lastSuccessEpochMillis,
            long lastDurationMillis,
            long publishedCount,
            long skippedLockedCount,
            long failureCount,
            String lastVersion,
            String lastError) {
    }

    private static final class ProjectionRuntimeState {
        private final String projection;
        private volatile boolean running;
        private volatile long lastStartedEpochMillis;
        private volatile long lastSuccessEpochMillis;
        private volatile long lastDurationMillis;
        private volatile long publishedCount;
        private volatile long skippedLockedCount;
        private volatile long failureCount;
        private volatile String lastVersion = "";
        private volatile String lastError = "";

        private ProjectionRuntimeState(String projection) {
            this.projection = projection;
        }

        void start() {
            running = true;
            lastStartedEpochMillis = System.currentTimeMillis();
        }

        void succeeded(ProjectionRefreshResult result) {
            publishedCount++;
            lastVersion = result.version();
            lastSuccessEpochMillis = System.currentTimeMillis();
            lastError = "";
        }

        void skipped() {
            skippedLockedCount++;
            lastSuccessEpochMillis = System.currentTimeMillis();
            lastError = "";
        }

        void failed(Throwable error) {
            failureCount++;
            String message = error.getMessage();
            lastError = error.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + limit(message, 512));
        }

        void finish() {
            lastDurationMillis = Math.max(0L, System.currentTimeMillis() - lastStartedEpochMillis);
            running = false;
        }

        ProjectionStatus snapshot() {
            return new ProjectionStatus(
                    projection,
                    running,
                    lastStartedEpochMillis,
                    lastSuccessEpochMillis,
                    lastDurationMillis,
                    publishedCount,
                    skippedLockedCount,
                    failureCount,
                    lastVersion,
                    lastError
            );
        }

        private static String limit(String value, int maxChars) {
            return value.length() <= maxChars ? value : value.substring(0, maxChars);
        }
    }

    public static final class Builder {
        private List<CacheWriterProjectionSettings> settings = List.of();
        private ProjectionRefresher refresher;
        private boolean runOnce;
        private int schedulerThreads = 1;
        private long schedulerThreadStackBytes = 256L * 1024L;
        private String threadNamePrefix = "cache-writer";
        private long firstRunTimeoutMillis = 60_000L;
        private ProjectionRefreshObserver observer = ProjectionRefreshObserver.console();

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

        public Builder schedulerThreadStackBytes(long schedulerThreadStackBytes) {
            if (schedulerThreadStackBytes < 0) {
                throw new IllegalArgumentException("schedulerThreadStackBytes must not be negative");
            }
            this.schedulerThreadStackBytes = schedulerThreadStackBytes;
            return this;
        }

        public Builder threadNamePrefix(String threadNamePrefix) {
            if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
                throw new IllegalArgumentException("threadNamePrefix must not be blank");
            }
            this.threadNamePrefix = threadNamePrefix.trim();
            return this;
        }

        public Builder firstRunTimeoutMillis(long firstRunTimeoutMillis) {
            if (firstRunTimeoutMillis <= 0) {
                throw new IllegalArgumentException("firstRunTimeoutMillis must be positive");
            }
            this.firstRunTimeoutMillis = firstRunTimeoutMillis;
            return this;
        }

        public Builder observer(ProjectionRefreshObserver observer) {
            this.observer = Objects.requireNonNull(observer, "observer");
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
            HashSet<String> names = new HashSet<>();
            for (CacheWriterProjectionSettings setting : settings) {
                if (!names.add(setting.name())) {
                    throw new IllegalArgumentException("Duplicate projection name: " + setting.name());
                }
            }
            return new ProjectionRefreshScheduler(this);
        }
    }
}
